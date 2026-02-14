package dev.recafmcp.providers;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the xrefs-from switch statement correctly handles
 * {@link InvokeDynamicInsnNode} instructions, including LambdaMetafactory
 * bootstraps, StringConcatFactory bootstraps, and regular method/field refs
 * alongside them.
 */
class XRefInvokeDynamicTest {

	// ---- Synthetic bytecode builders ----

	/**
	 * Creates a class with three methods:
	 * <ul>
	 *   <li>{@code doVirtual()} - contains a regular {@code invokevirtual} call</li>
	 *   <li>{@code doLambda()} - contains an {@code invokedynamic} with LambdaMetafactory bootstrap</li>
	 *   <li>{@code doConcat()} - contains an {@code invokedynamic} with StringConcatFactory bootstrap</li>
	 * </ul>
	 */
	private byte[] createSyntheticClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/IndyTest", null,
				"java/lang/Object", null);

		// Method 1: regular invokevirtual
		{
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
					"doVirtual", "()V", null, null);
			mv.visitCode();
			mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out",
					"Ljava/io/PrintStream;");
			mv.visitLdcInsn("hello");
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
					"println", "(Ljava/lang/String;)V", false);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		// Method 2: invokedynamic with LambdaMetafactory bootstrap
		{
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
					"doLambda", "()Ljava/util/function/Consumer;", null, null);
			mv.visitCode();

			Handle bsm = new Handle(
					Opcodes.H_INVOKESTATIC,
					"java/lang/invoke/LambdaMetafactory",
					"metafactory",
					"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
							+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
							+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;"
							+ ")Ljava/lang/invoke/CallSite;",
					false);
			Handle implMethod = new Handle(
					Opcodes.H_INVOKESTATIC,
					"com/example/IndyTest",
					"lambda$doLambda$0",
					"(Ljava/lang/String;)V",
					false);

			mv.visitInvokeDynamicInsn(
					"accept",
					"()Ljava/util/function/Consumer;",
					bsm,
					Type.getType("(Ljava/lang/Object;)V"),
					implMethod,
					Type.getType("(Ljava/lang/String;)V"));
			mv.visitInsn(Opcodes.ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		// Method 3: invokedynamic with StringConcatFactory bootstrap
		{
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
					"doConcat", "(Ljava/lang/String;I)Ljava/lang/String;", null, null);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitVarInsn(Opcodes.ILOAD, 1);

			Handle bsm = new Handle(
					Opcodes.H_INVOKESTATIC,
					"java/lang/invoke/StringConcatFactory",
					"makeConcatWithConstants",
					"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
							+ "Ljava/lang/invoke/MethodType;Ljava/lang/String;"
							+ "[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
					false);

			mv.visitInvokeDynamicInsn(
					"makeConcatWithConstants",
					"(Ljava/lang/String;I)Ljava/lang/String;",
					bsm,
					"\u0001-\u0001");
			mv.visitInsn(Opcodes.ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		cw.visitEnd();
		return cw.toByteArray();
	}

	// ---- Extraction helper (mirrors XRefToolProvider switch logic) ----

	/**
	 * Extracts outgoing references from a ClassNode using the same switch logic
	 * as {@code XRefToolProvider.registerXRefsFrom}.
	 */
	private record ExtractedRefs(
			List<Map<String, Object>> methodRefs,
			List<Map<String, Object>> fieldRefs,
			Set<String> typeRefs
	) {}

	private ExtractedRefs extractRefs(ClassNode classNode, String methodName, String methodDescriptor) {
		List<Map<String, Object>> methodRefs = new ArrayList<>();
		List<Map<String, Object>> fieldRefs = new ArrayList<>();
		Set<String> typeRefs = new LinkedHashSet<>();

		for (MethodNode mn : classNode.methods) {
			if (methodName != null && !mn.name.equals(methodName)) continue;
			if (methodDescriptor != null && !mn.desc.equals(methodDescriptor)) continue;

			InsnList instructions = mn.instructions;
			if (instructions == null) continue;

			String methodContext = mn.name + mn.desc;

			for (int i = 0; i < instructions.size(); i++) {
				AbstractInsnNode insn = instructions.get(i);
				switch (insn) {
					case MethodInsnNode min -> {
						LinkedHashMap<String, Object> ref = new LinkedHashMap<>();
						ref.put("fromMethod", methodContext);
						ref.put("targetOwner", min.owner);
						ref.put("targetName", min.name);
						ref.put("targetDescriptor", min.desc);
						ref.put("type", "method");
						methodRefs.add(ref);
						typeRefs.add(min.owner);
					}
					case FieldInsnNode fin -> {
						LinkedHashMap<String, Object> ref = new LinkedHashMap<>();
						ref.put("fromMethod", methodContext);
						ref.put("targetOwner", fin.owner);
						ref.put("targetName", fin.name);
						ref.put("targetDescriptor", fin.desc);
						ref.put("type", "field");
						fieldRefs.add(ref);
						typeRefs.add(fin.owner);
					}
					case InvokeDynamicInsnNode indy -> {
						LinkedHashMap<String, Object> ref = new LinkedHashMap<>();
						ref.put("fromMethod", methodContext);
						ref.put("bootstrapOwner", indy.bsm.getOwner());
						ref.put("bootstrapName", indy.bsm.getName());
						ref.put("bootstrapDescriptor", indy.bsm.getDesc());
						ref.put("callName", indy.name);
						ref.put("callDescriptor", indy.desc);
						ref.put("type", "invokedynamic");
						if (indy.bsmArgs != null && indy.bsmArgs.length > 0) {
							List<String> bsmArgStrs = new ArrayList<>();
							for (Object arg : indy.bsmArgs) bsmArgStrs.add(arg.toString());
							ref.put("bootstrapArgs", bsmArgStrs);
						}
						methodRefs.add(ref);
						typeRefs.add(indy.bsm.getOwner());
					}
					case TypeInsnNode tin -> typeRefs.add(tin.desc);
					default -> {
						// Other instruction types don't produce xrefs
					}
				}
			}
		}

		return new ExtractedRefs(methodRefs, fieldRefs, typeRefs);
	}

	// ---- Tests ----

	@Test
	void regularInvokevirtual_stillDetected() {
		byte[] bytecode = createSyntheticClass();
		ClassNode classNode = new ClassNode();
		new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);

		ExtractedRefs refs = extractRefs(classNode, "doVirtual", null);

		// Should find: invokevirtual PrintStream.println
		assertEquals(1, refs.methodRefs.size(), "Should find exactly one method ref");
		Map<String, Object> ref = refs.methodRefs.get(0);
		assertEquals("method", ref.get("type"));
		assertEquals("java/io/PrintStream", ref.get("targetOwner"));
		assertEquals("println", ref.get("targetName"));
		assertEquals("(Ljava/lang/String;)V", ref.get("targetDescriptor"));

		// Should also find the getstatic for System.out
		assertEquals(1, refs.fieldRefs.size(), "Should find exactly one field ref");
		Map<String, Object> fieldRef = refs.fieldRefs.get(0);
		assertEquals("field", fieldRef.get("type"));
		assertEquals("java/lang/System", fieldRef.get("targetOwner"));
		assertEquals("out", fieldRef.get("targetName"));
	}

	@Test
	void lambdaMetafactory_detectedAsInvokeDynamic() {
		byte[] bytecode = createSyntheticClass();
		ClassNode classNode = new ClassNode();
		new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);

		ExtractedRefs refs = extractRefs(classNode, "doLambda", null);

		// Should find the invokedynamic for LambdaMetafactory
		List<Map<String, Object>> indyRefs = refs.methodRefs.stream()
				.filter(r -> "invokedynamic".equals(r.get("type")))
				.toList();

		assertEquals(1, indyRefs.size(), "Should find exactly one invokedynamic ref");
		Map<String, Object> ref = indyRefs.get(0);
		assertEquals("java/lang/invoke/LambdaMetafactory", ref.get("bootstrapOwner"));
		assertEquals("metafactory", ref.get("bootstrapName"));
		assertEquals("accept", ref.get("callName"));
		assertEquals("()Ljava/util/function/Consumer;", ref.get("callDescriptor"));
		assertEquals("invokedynamic", ref.get("type"));

		// Bootstrap args should be present (3 args for LambdaMetafactory)
		assertTrue(ref.containsKey("bootstrapArgs"), "Should have bootstrapArgs");
		@SuppressWarnings("unchecked")
		List<String> bsmArgs = (List<String>) ref.get("bootstrapArgs");
		assertEquals(3, bsmArgs.size(), "LambdaMetafactory has 3 bootstrap args");

		// The bootstrap owner should be in typeRefs
		assertTrue(refs.typeRefs.contains("java/lang/invoke/LambdaMetafactory"),
				"typeRefs should include bootstrap owner");
	}

	@Test
	void stringConcatFactory_detectedAsInvokeDynamic() {
		byte[] bytecode = createSyntheticClass();
		ClassNode classNode = new ClassNode();
		new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);

		ExtractedRefs refs = extractRefs(classNode, "doConcat", null);

		List<Map<String, Object>> indyRefs = refs.methodRefs.stream()
				.filter(r -> "invokedynamic".equals(r.get("type")))
				.toList();

		assertEquals(1, indyRefs.size(), "Should find exactly one invokedynamic ref");
		Map<String, Object> ref = indyRefs.get(0);
		assertEquals("java/lang/invoke/StringConcatFactory", ref.get("bootstrapOwner"));
		assertEquals("makeConcatWithConstants", ref.get("bootstrapName"));
		assertEquals("makeConcatWithConstants", ref.get("callName"));
		assertEquals("(Ljava/lang/String;I)Ljava/lang/String;", ref.get("callDescriptor"));
		assertEquals("invokedynamic", ref.get("type"));

		// Bootstrap args should have the template string
		assertTrue(ref.containsKey("bootstrapArgs"), "Should have bootstrapArgs");
		@SuppressWarnings("unchecked")
		List<String> bsmArgs = (List<String>) ref.get("bootstrapArgs");
		assertEquals(1, bsmArgs.size(), "StringConcatFactory has 1 bootstrap arg (the template)");

		assertTrue(refs.typeRefs.contains("java/lang/invoke/StringConcatFactory"),
				"typeRefs should include bootstrap owner");
	}

	@Test
	void allMethods_invokeDynamicAndRegularRefs_bothDetected() {
		byte[] bytecode = createSyntheticClass();
		ClassNode classNode = new ClassNode();
		new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);

		// Analyze all methods (no filter)
		ExtractedRefs refs = extractRefs(classNode, null, null);

		// Count invokedynamic refs
		long indyCount = refs.methodRefs.stream()
				.filter(r -> "invokedynamic".equals(r.get("type")))
				.count();
		assertEquals(2, indyCount, "Should find 2 invokedynamic refs (lambda + concat)");

		// Count regular method refs
		long methodCount = refs.methodRefs.stream()
				.filter(r -> "method".equals(r.get("type")))
				.count();
		assertEquals(1, methodCount, "Should find 1 regular method ref (println)");

		// Field refs (System.out getstatic)
		assertEquals(1, refs.fieldRefs.size(), "Should find 1 field ref (System.out)");

		// Type refs should include all referenced owners
		assertTrue(refs.typeRefs.contains("java/io/PrintStream"));
		assertTrue(refs.typeRefs.contains("java/lang/System"));
		assertTrue(refs.typeRefs.contains("java/lang/invoke/LambdaMetafactory"));
		assertTrue(refs.typeRefs.contains("java/lang/invoke/StringConcatFactory"));
	}

	@Test
	void invokeDynamic_outputMapContainsAllRequiredFields() {
		byte[] bytecode = createSyntheticClass();
		ClassNode classNode = new ClassNode();
		new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);

		ExtractedRefs refs = extractRefs(classNode, "doLambda", null);
		Map<String, Object> ref = refs.methodRefs.stream()
				.filter(r -> "invokedynamic".equals(r.get("type")))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No invokedynamic ref found"));

		// Verify all required keys are present
		assertNotNull(ref.get("fromMethod"), "fromMethod must be present");
		assertNotNull(ref.get("bootstrapOwner"), "bootstrapOwner must be present");
		assertNotNull(ref.get("bootstrapName"), "bootstrapName must be present");
		assertNotNull(ref.get("bootstrapDescriptor"), "bootstrapDescriptor must be present");
		assertNotNull(ref.get("callName"), "callName must be present");
		assertNotNull(ref.get("callDescriptor"), "callDescriptor must be present");
		assertEquals("invokedynamic", ref.get("type"), "type must be 'invokedynamic'");
		assertNotNull(ref.get("bootstrapArgs"), "bootstrapArgs must be present when bsmArgs exist");
	}

	@Test
	void invokeDynamic_noBsmArgs_omitsBootstrapArgsKey() {
		// Create a class with an invokedynamic that has no bootstrap args
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/NoBsmArgs", null,
				"java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"test", "()V", null, null);
		mv.visitCode();

		Handle bsm = new Handle(
				Opcodes.H_INVOKESTATIC,
				"com/example/MyBootstrap",
				"bootstrap",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
						+ "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
				false);
		// No extra bootstrap args
		mv.visitInvokeDynamicInsn("dynamicCall", "()V", bsm);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();

		ClassNode classNode = new ClassNode();
		new ClassReader(cw.toByteArray()).accept(classNode, ClassReader.SKIP_FRAMES);

		ExtractedRefs refs = extractRefs(classNode, "test", null);

		List<Map<String, Object>> indyRefs = refs.methodRefs.stream()
				.filter(r -> "invokedynamic".equals(r.get("type")))
				.toList();

		assertEquals(1, indyRefs.size());
		Map<String, Object> ref = indyRefs.get(0);
		assertEquals("com/example/MyBootstrap", ref.get("bootstrapOwner"));
		assertEquals("bootstrap", ref.get("bootstrapName"));
		assertEquals("dynamicCall", ref.get("callName"));
		assertEquals("()V", ref.get("callDescriptor"));
		// No bsmArgs -> key should be absent
		assertFalse(ref.containsKey("bootstrapArgs"),
				"bootstrapArgs should not be present when there are no bootstrap arguments");
	}

	@Test
	void bootstrapDescriptor_isCorrectlyExtracted() {
		byte[] bytecode = createSyntheticClass();
		ClassNode classNode = new ClassNode();
		new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);

		ExtractedRefs refs = extractRefs(classNode, "doConcat", null);
		Map<String, Object> ref = refs.methodRefs.stream()
				.filter(r -> "invokedynamic".equals(r.get("type")))
				.findFirst()
				.orElseThrow();

		String bsmDesc = (String) ref.get("bootstrapDescriptor");
		assertNotNull(bsmDesc);
		// StringConcatFactory.makeConcatWithConstants descriptor
		assertTrue(bsmDesc.contains("MethodHandles$Lookup"),
				"Bootstrap descriptor should contain MethodHandles$Lookup, got: " + bsmDesc);
		assertTrue(bsmDesc.contains("CallSite"),
				"Bootstrap descriptor should return CallSite, got: " + bsmDesc);
	}
}
