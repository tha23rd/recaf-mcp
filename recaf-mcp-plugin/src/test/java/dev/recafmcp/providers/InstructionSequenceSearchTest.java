package dev.recafmcp.providers;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.query.InstructionQuery;
import software.coley.recaf.util.BlwUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the search-instruction-sequence tool functionality.
 * Validates pattern compilation, InstructionQuery construction, and
 * multi-instruction sequence matching against synthetic bytecode.
 */
class InstructionSequenceSearchTest {

	// ---- Pattern compilation tests ----

	@Test
	void patternCompilation_singlePattern() {
		String patternStr = "invokestatic java/lang/System\\.currentTimeMillis";
		Pattern compiled = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
		StringPredicate predicate = new StringPredicate("regex-partial",
				s -> s != null && compiled.matcher(s).find());

		// Should match JASM-formatted instruction text
		assertTrue(predicate.match("invokestatic java/lang/System.currentTimeMillis ()J"));
		assertFalse(predicate.match("invokevirtual java/io/PrintStream.println (Ljava/lang/String;)V"));
	}

	@Test
	void patternCompilation_wildcardPattern() {
		String patternStr = ".*";
		Pattern compiled = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
		StringPredicate predicate = new StringPredicate("regex-partial",
				s -> s != null && compiled.matcher(s).find());

		// Wildcard should match anything
		assertTrue(predicate.match("aload 0"));
		assertTrue(predicate.match("invokevirtual java/io/PrintStream.println (Ljava/lang/String;)V"));
		assertTrue(predicate.match("ldc 42"));
	}

	@Test
	void patternCompilation_caseInsensitive() {
		String patternStr = "INVOKESTATIC";
		Pattern compiled = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
		StringPredicate predicate = new StringPredicate("regex-partial",
				s -> s != null && compiled.matcher(s).find());

		assertTrue(predicate.match("invokestatic java/lang/System.currentTimeMillis ()J"));
		assertTrue(predicate.match("INVOKESTATIC java/lang/System.currentTimeMillis ()J"));
	}

	@Test
	void patternCompilation_partialMatch() {
		String patternStr = "println";
		Pattern compiled = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
		StringPredicate predicate = new StringPredicate("regex-partial",
				s -> s != null && compiled.matcher(s).find());

		assertTrue(predicate.match("invokevirtual java/io/PrintStream.println (Ljava/lang/String;)V"));
		assertFalse(predicate.match("invokevirtual java/io/PrintStream.print (Ljava/lang/String;)V"));
	}

	@Test
	void patternCompilation_nullInputThrowsNpe() {
		// StringPredicate.match() requires non-null text (enforced by Objects.requireNonNull)
		// This is correct behavior since BlwUtil.toString() never returns null
		String patternStr = "test";
		Pattern compiled = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
		StringPredicate predicate = new StringPredicate("regex-partial",
				s -> s != null && compiled.matcher(s).find());

		assertThrows(NullPointerException.class, () -> predicate.match(null));
	}

	// ---- InstructionQuery construction tests ----

	@Test
	void instructionQuery_constructsWithSinglePredicate() {
		List<StringPredicate> predicates = new ArrayList<>();
		Pattern compiled = Pattern.compile("invokestatic", Pattern.CASE_INSENSITIVE);
		predicates.add(new StringPredicate("regex-partial",
				s -> s != null && compiled.matcher(s).find()));

		InstructionQuery query = new InstructionQuery(predicates);
		assertNotNull(query);
	}

	@Test
	void instructionQuery_constructsWithMultiplePredicates() {
		List<StringPredicate> predicates = new ArrayList<>();
		String[] patterns = {"invokestatic.*currentTimeMillis", ".*", "lcmp"};
		for (String p : patterns) {
			Pattern compiled = Pattern.compile(p, Pattern.CASE_INSENSITIVE);
			predicates.add(new StringPredicate("regex-partial",
					s -> s != null && compiled.matcher(s).find()));
		}

		InstructionQuery query = new InstructionQuery(predicates);
		assertNotNull(query);
	}

	@Test
	void instructionQuery_rejectsNullPredicates() {
		assertThrows(NullPointerException.class, () -> new InstructionQuery(null));
	}

	// ---- Synthetic bytecode matching tests ----

	/**
	 * Create a synthetic class with a method containing the instruction sequence:
	 * invokestatic System.currentTimeMillis, lstore, invokestatic System.currentTimeMillis, lstore, lload, lload, lcmp
	 * This simulates a time-bomb pattern.
	 */
	private byte[] createTimeBombClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/TimeBomb", null,
				"java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"check", "()Z", null, null);
		mv.visitCode();

		// long start = System.currentTimeMillis();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System",
				"currentTimeMillis", "()J", false);
		mv.visitVarInsn(Opcodes.LSTORE, 0);

		// long end = System.currentTimeMillis();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System",
				"currentTimeMillis", "()J", false);
		mv.visitVarInsn(Opcodes.LSTORE, 2);

		// return start < end (uses lcmp)
		mv.visitVarInsn(Opcodes.LLOAD, 0);
		mv.visitVarInsn(Opcodes.LLOAD, 2);
		mv.visitInsn(Opcodes.LCMP);
		mv.visitInsn(Opcodes.IRETURN);

		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * Create a simple class with System.out.println("Hello")
	 */
	private byte[] createHelloWorldClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Hello", null,
				"java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"greet", "()V", null, null);
		mv.visitCode();
		mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out",
				"Ljava/io/PrintStream;");
		mv.visitLdcInsn("Hello");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
				"println", "(Ljava/lang/String;)V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void blwUtil_timeBombInstructionSequence_hasExpectedInstructions() {
		byte[] bytecode = createTimeBombClass();
		ClassNode classNode = new ClassNode();
		new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);

		MethodNode checkMethod = classNode.methods.stream()
				.filter(m -> m.name.equals("check"))
				.findFirst()
				.orElseThrow();

		// Verify instruction count and content
		InsnList instructions = checkMethod.instructions;
		assertNotNull(instructions);
		assertTrue(instructions.size() > 0, "Method should have instructions");

		// Collect all JASM-formatted instruction text
		List<String> insnTexts = new ArrayList<>();
		for (int i = 0; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);
			if (insn.getOpcode() >= 0) {
				insnTexts.add(BlwUtil.toString(insn));
			}
		}

		// Should have at least the key instructions
		assertTrue(insnTexts.size() >= 7, "Should have at least 7 real instructions, got: " + insnTexts.size());

		// Check that key instructions are present
		boolean hasCurrentTimeMillis = insnTexts.stream()
				.anyMatch(s -> s.toLowerCase().contains("invokestatic") && s.contains("currentTimeMillis"));
		boolean hasLcmp = insnTexts.stream()
				.anyMatch(s -> s.toLowerCase().contains("lcmp"));

		assertTrue(hasCurrentTimeMillis, "Should contain invokestatic currentTimeMillis in: " + insnTexts);
		assertTrue(hasLcmp, "Should contain lcmp in: " + insnTexts);
	}

	@Test
	void blwUtil_helloWorldSequence_hasGetstaticLdcInvokevirtual() {
		byte[] bytecode = createHelloWorldClass();
		ClassNode classNode = new ClassNode();
		new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);

		MethodNode greetMethod = classNode.methods.stream()
				.filter(m -> m.name.equals("greet"))
				.findFirst()
				.orElseThrow();

		List<String> insnTexts = new ArrayList<>();
		InsnList instructions = greetMethod.instructions;
		for (int i = 0; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);
			if (insn.getOpcode() >= 0) {
				insnTexts.add(BlwUtil.toString(insn));
			}
		}

		// Should have: getstatic System.out, ldc "Hello", invokevirtual println, return
		assertTrue(insnTexts.size() >= 4, "Should have at least 4 instructions, got: " + insnTexts.size());

		// Verify the getstatic -> ldc -> invokevirtual sequence exists
		boolean foundSequence = false;
		for (int i = 0; i < insnTexts.size() - 2; i++) {
			if (insnTexts.get(i).toLowerCase().contains("getstatic") &&
					insnTexts.get(i + 1).toLowerCase().contains("ldc") &&
					insnTexts.get(i + 2).toLowerCase().contains("invokevirtual")) {
				foundSequence = true;
				break;
			}
		}
		assertTrue(foundSequence, "Should find getstatic->ldc->invokevirtual sequence in: " + insnTexts);
	}

	@Test
	void multiPatternMatch_manualScan_findsTimeBombPattern() {
		// This test manually implements the matching logic that InstructionQuery uses
		// to verify the pattern matching approach works on our synthetic bytecode
		byte[] bytecode = createTimeBombClass();
		ClassNode classNode = new ClassNode();
		new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);

		MethodNode checkMethod = classNode.methods.stream()
				.filter(m -> m.name.equals("check"))
				.findFirst()
				.orElseThrow();

		// Build predicates for a 3-instruction sequence: currentTimeMillis, <any>, currentTimeMillis
		String[] patternStrs = {
				"invokestatic.*currentTimeMillis",
				".*",
				"invokestatic.*currentTimeMillis"
		};
		List<StringPredicate> predicates = new ArrayList<>();
		for (String p : patternStrs) {
			Pattern compiled = Pattern.compile(p, Pattern.CASE_INSENSITIVE);
			predicates.add(new StringPredicate("regex-partial",
					s -> s != null && compiled.matcher(s).find()));
		}

		// Manually scan for matching sequences (mirrors InstructionQuery logic)
		InsnList instructions = checkMethod.instructions;
		List<String> allTexts = new ArrayList<>();
		for (int i = 0; i < instructions.size(); i++) {
			allTexts.add(BlwUtil.toString(instructions.get(i)));
		}

		boolean foundMatch = false;
		for (int i = 0; i <= allTexts.size() - predicates.size(); i++) {
			boolean allMatch = true;
			for (int j = 0; j < predicates.size(); j++) {
				if (!predicates.get(j).match(allTexts.get(i + j))) {
					allMatch = false;
					break;
				}
			}
			if (allMatch) {
				foundMatch = true;
				break;
			}
		}

		assertTrue(foundMatch, "Should find currentTimeMillis -> * -> currentTimeMillis sequence in: " + allTexts);
	}

	@Test
	void multiPatternMatch_manualScan_noMatchForNonexistentPattern() {
		byte[] bytecode = createHelloWorldClass();
		ClassNode classNode = new ClassNode();
		new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);

		MethodNode greetMethod = classNode.methods.stream()
				.filter(m -> m.name.equals("greet"))
				.findFirst()
				.orElseThrow();

		// Pattern that should NOT match in the hello world class
		String[] patternStrs = {"invokestatic.*currentTimeMillis", "lcmp"};
		List<StringPredicate> predicates = new ArrayList<>();
		for (String p : patternStrs) {
			Pattern compiled = Pattern.compile(p, Pattern.CASE_INSENSITIVE);
			predicates.add(new StringPredicate("regex-partial",
					s -> s != null && compiled.matcher(s).find()));
		}

		InsnList instructions = greetMethod.instructions;
		List<String> allTexts = new ArrayList<>();
		for (int i = 0; i < instructions.size(); i++) {
			allTexts.add(BlwUtil.toString(instructions.get(i)));
		}

		boolean foundMatch = false;
		for (int i = 0; i <= allTexts.size() - predicates.size(); i++) {
			boolean allMatch = true;
			for (int j = 0; j < predicates.size(); j++) {
				if (!predicates.get(j).match(allTexts.get(i + j))) {
					allMatch = false;
					break;
				}
			}
			if (allMatch) {
				foundMatch = true;
				break;
			}
		}

		assertFalse(foundMatch, "Should NOT find currentTimeMillis->lcmp in hello world class");
	}

	@Test
	void singlePatternSequence_manualScan_matchesSingleInstruction() {
		byte[] bytecode = createHelloWorldClass();
		ClassNode classNode = new ClassNode();
		new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);

		MethodNode greetMethod = classNode.methods.stream()
				.filter(m -> m.name.equals("greet"))
				.findFirst()
				.orElseThrow();

		// Single pattern should work like search-instructions
		String[] patternStrs = {"getstatic.*System.*out"};
		List<StringPredicate> predicates = new ArrayList<>();
		for (String p : patternStrs) {
			Pattern compiled = Pattern.compile(p, Pattern.CASE_INSENSITIVE);
			predicates.add(new StringPredicate("regex-partial",
					s -> s != null && compiled.matcher(s).find()));
		}

		InsnList instructions = greetMethod.instructions;
		int matchCount = 0;
		for (int i = 0; i < instructions.size(); i++) {
			String text = BlwUtil.toString(instructions.get(i));
			if (predicates.get(0).match(text)) {
				matchCount++;
			}
		}

		assertEquals(1, matchCount, "Should find exactly one getstatic System.out");
	}

	// ---- getStringList helper tests (via reflection or direct logic) ----

	@Test
	void patternList_emptyList_isDetected() {
		List<String> patterns = List.of();
		assertTrue(patterns.isEmpty());
	}

	@Test
	void patternList_singleElement() {
		List<String> patterns = List.of("invokestatic");
		assertEquals(1, patterns.size());
		assertDoesNotThrow(() -> Pattern.compile(patterns.get(0), Pattern.CASE_INSENSITIVE));
	}

	@Test
	void patternList_invalidRegex_throwsException() {
		String badPattern = "[invalid";
		assertThrows(java.util.regex.PatternSyntaxException.class,
				() -> Pattern.compile(badPattern, Pattern.CASE_INSENSITIVE));
	}
}
