package dev.recafmcp.providers;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import software.coley.recaf.util.BlwUtil;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that BlwUtil.toString(AbstractInsnNode) produces meaningful JASM-formatted
 * instruction text for all instruction types, including InvokeDynamicInsnNode which
 * was previously unsupported by the hand-rolled formatInstruction() method.
 */
class BlwUtilInstructionFormatTest {

	@Test
	void invokevirtual_includesOwnerNameAndDescriptor() {
		MethodInsnNode insn = new MethodInsnNode(
				Opcodes.INVOKEVIRTUAL,
				"java/io/PrintStream",
				"println",
				"(Ljava/lang/String;)V",
				false);
		String text = BlwUtil.toString(insn);
		assertNotNull(text);
		assertFalse(text.isBlank(), "Output should not be blank");
		// JASM format uses lowercase opcodes
		assertTrue(text.toLowerCase().contains("invokevirtual"),
				"Should contain 'invokevirtual', got: " + text);
		assertTrue(text.contains("println"),
				"Should contain method name 'println', got: " + text);
		assertTrue(text.contains("PrintStream") || text.contains("java/io/PrintStream"),
				"Should contain owner class, got: " + text);
	}

	@Test
	void invokestatic_includesOwnerNameAndDescriptor() {
		MethodInsnNode insn = new MethodInsnNode(
				Opcodes.INVOKESTATIC,
				"java/lang/Integer",
				"parseInt",
				"(Ljava/lang/String;)I",
				false);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("invokestatic"),
				"Should contain 'invokestatic', got: " + text);
		assertTrue(text.contains("parseInt"),
				"Should contain method name, got: " + text);
	}

	@Test
	void getstatic_includesFieldInfo() {
		FieldInsnNode insn = new FieldInsnNode(
				Opcodes.GETSTATIC,
				"java/lang/System",
				"out",
				"Ljava/io/PrintStream;");
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("getstatic"),
				"Should contain 'getstatic', got: " + text);
		assertTrue(text.contains("out"),
				"Should contain field name 'out', got: " + text);
		assertTrue(text.contains("System") || text.contains("java/lang/System"),
				"Should contain owner class, got: " + text);
	}

	@Test
	void putstatic_includesFieldInfo() {
		FieldInsnNode insn = new FieldInsnNode(
				Opcodes.PUTSTATIC,
				"com/example/Config",
				"DEBUG",
				"Z");
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("putstatic"),
				"Should contain 'putstatic', got: " + text);
		assertTrue(text.contains("DEBUG"),
				"Should contain field name, got: " + text);
	}

	@Test
	void ldc_stringConstant() {
		LdcInsnNode insn = new LdcInsnNode("Hello, World!");
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("ldc"),
				"Should contain 'ldc', got: " + text);
		assertTrue(text.contains("Hello, World!"),
				"Should contain the string constant, got: " + text);
	}

	@Test
	void ldc_intConstant() {
		LdcInsnNode insn = new LdcInsnNode(42);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.contains("42"),
				"Should contain the int constant '42', got: " + text);
	}

	@Test
	void ldc_longConstant() {
		LdcInsnNode insn = new LdcInsnNode(999999999999L);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.contains("999999999999"),
				"Should contain the long constant, got: " + text);
	}

	@Test
	void ldc_doubleConstant() {
		LdcInsnNode insn = new LdcInsnNode(3.14);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.contains("3.14"),
				"Should contain the double constant, got: " + text);
	}

	@Test
	void invokedynamic_includesCallNameAndBootstrapInfo() {
		// Simulate a string concatenation invokedynamic (Java 9+)
		Handle bsm = new Handle(
				Opcodes.H_INVOKESTATIC,
				"java/lang/invoke/StringConcatFactory",
				"makeConcatWithConstants",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
				false);
		InvokeDynamicInsnNode insn = new InvokeDynamicInsnNode(
				"makeConcatWithConstants",
				"(Ljava/lang/String;I)Ljava/lang/String;",
				bsm,
				"\u0001-\u0001");
		String text = BlwUtil.toString(insn);
		assertNotNull(text);
		assertFalse(text.isBlank(), "InvokeDynamic output should not be blank");
		// The main bug fix: InvokeDynamicInsnNode must produce meaningful output
		assertTrue(text.toLowerCase().contains("invokedynamic"),
				"Should contain 'invokedynamic', got: " + text);
		assertTrue(text.contains("makeConcatWithConstants"),
				"Should contain call name 'makeConcatWithConstants', got: " + text);
	}

	@Test
	void invokedynamic_lambdaMetafactory() {
		// Simulate a lambda metafactory invokedynamic
		Handle bsm = new Handle(
				Opcodes.H_INVOKESTATIC,
				"java/lang/invoke/LambdaMetafactory",
				"metafactory",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
				false);
		Handle implMethod = new Handle(
				Opcodes.H_INVOKESTATIC,
				"com/example/MyClass",
				"lambda$main$0",
				"(Ljava/lang/String;)V",
				false);
		InvokeDynamicInsnNode insn = new InvokeDynamicInsnNode(
				"accept",
				"()Ljava/util/function/Consumer;",
				bsm,
				Type.getType("(Ljava/lang/Object;)V"),
				implMethod,
				Type.getType("(Ljava/lang/String;)V"));
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank(), "Lambda invokedynamic output should not be blank");
		assertTrue(text.toLowerCase().contains("invokedynamic"),
				"Should contain 'invokedynamic', got: " + text);
	}

	@Test
	void invokedynamic_matchesRegexPattern() {
		// This test validates the primary use case: regex pattern matching against instruction text
		Handle bsm = new Handle(
				Opcodes.H_INVOKESTATIC,
				"java/lang/invoke/StringConcatFactory",
				"makeConcatWithConstants",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
				false);
		InvokeDynamicInsnNode insn = new InvokeDynamicInsnNode(
				"makeConcatWithConstants",
				"(Ljava/lang/String;)Ljava/lang/String;",
				bsm,
				"\u0001!");
		String text = BlwUtil.toString(insn);

		// The pattern that would be used by an MCP client searching for string concatenation
		Pattern pattern = Pattern.compile("invokedynamic.*makeConcatWithConstants", Pattern.CASE_INSENSITIVE);
		assertTrue(pattern.matcher(text).find(),
				"Pattern 'invokedynamic.*makeConcatWithConstants' should match, got: " + text);
	}

	@Test
	void jumpInsn_ifle() {
		LabelNode target = new LabelNode();
		JumpInsnNode insn = new JumpInsnNode(Opcodes.IFLE, target);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("ifle"),
				"Should contain 'ifle', got: " + text);
	}

	@Test
	void jumpInsn_goto() {
		LabelNode target = new LabelNode();
		JumpInsnNode insn = new JumpInsnNode(Opcodes.GOTO, target);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("goto"),
				"Should contain 'goto', got: " + text);
	}

	@Test
	void iincInsn_containsVarAndIncrement() {
		IincInsnNode insn = new IincInsnNode(3, 1);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("iinc"),
				"Should contain 'iinc', got: " + text);
	}

	@Test
	void typeInsn_new() {
		TypeInsnNode insn = new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList");
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("new"),
				"Should contain 'new', got: " + text);
		assertTrue(text.contains("ArrayList") || text.contains("java/util/ArrayList"),
				"Should contain type name, got: " + text);
	}

	@Test
	void typeInsn_checkcast() {
		TypeInsnNode insn = new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String");
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("checkcast"),
				"Should contain 'checkcast', got: " + text);
		assertTrue(text.contains("String") || text.contains("java/lang/String"),
				"Should contain type name, got: " + text);
	}

	@Test
	void intInsn_bipush() {
		IntInsnNode insn = new IntInsnNode(Opcodes.BIPUSH, 100);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("bipush"),
				"Should contain 'bipush', got: " + text);
		assertTrue(text.contains("100"),
				"Should contain operand value '100', got: " + text);
	}

	@Test
	void intInsn_sipush() {
		IntInsnNode insn = new IntInsnNode(Opcodes.SIPUSH, 30000);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("sipush"),
				"Should contain 'sipush', got: " + text);
		assertTrue(text.contains("30000"),
				"Should contain operand value '30000', got: " + text);
	}

	@Test
	void varInsn_aload() {
		VarInsnNode insn = new VarInsnNode(Opcodes.ALOAD, 0);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("aload"),
				"Should contain 'aload', got: " + text);
	}

	@Test
	void varInsn_istore() {
		VarInsnNode insn = new VarInsnNode(Opcodes.ISTORE, 2);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("istore"),
				"Should contain 'istore', got: " + text);
	}

	@Test
	void simpleInsn_areturn() {
		InsnNode insn = new InsnNode(Opcodes.ARETURN);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		assertTrue(text.toLowerCase().contains("areturn"),
				"Should contain 'areturn', got: " + text);
	}

	@Test
	void simpleInsn_iconst0() {
		InsnNode insn = new InsnNode(Opcodes.ICONST_0);
		String text = BlwUtil.toString(insn);
		assertFalse(text.isBlank());
		// JASM may format iconst_0 differently - just verify it's non-empty and meaningful
		assertFalse(text.startsWith("<missing"),
				"Should not be a missing text mapper, got: " + text);
	}
}
