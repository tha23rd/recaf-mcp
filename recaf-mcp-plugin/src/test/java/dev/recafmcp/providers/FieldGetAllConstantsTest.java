package dev.recafmcp.providers;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.member.FieldMember;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the field-get-all-constants tool logic.
 * <p>
 * Uses ASM-generated synthetic classes parsed through Recaf's JvmClassInfoBuilder
 * to verify filtering behavior for constant extraction.
 */
class FieldGetAllConstantsTest {

	/**
	 * Build a synthetic class with a mix of fields:
	 * <ul>
	 *   <li>2 static final fields WITH ConstantValue (int=42, String="hello")</li>
	 *   <li>1 static field WITHOUT ConstantValue</li>
	 *   <li>1 instance final field WITH ConstantValue (long=99L)</li>
	 *   <li>1 instance field WITHOUT ConstantValue</li>
	 * </ul>
	 */
	private static JvmClassInfo buildMixedFieldClass() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/MixedConstants", null,
				"java/lang/Object", null);

		// Static final int with ConstantValue = 42
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"MAGIC_NUMBER", "I", null, Integer.valueOf(42)
		).visitEnd();

		// Static final String with ConstantValue = "hello"
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"GREETING", "Ljava/lang/String;", null, "hello"
		).visitEnd();

		// Static field WITHOUT ConstantValue
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"counter", "I", null, null
		).visitEnd();

		// Instance final field WITH ConstantValue = 99L
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
				"instanceConst", "J", null, Long.valueOf(99L)
		).visitEnd();

		// Instance field WITHOUT ConstantValue
		cw.visitField(
				Opcodes.ACC_PUBLIC,
				"data", "Ljava/lang/String;", null, null
		).visitEnd();

		cw.visitEnd();
		return new JvmClassInfoBuilder(cw.toByteArray()).build();
	}

	/**
	 * Build a synthetic class with no fields at all.
	 */
	private static JvmClassInfo buildEmptyClass() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Empty", null,
				"java/lang/Object", null);
		cw.visitEnd();
		return new JvmClassInfoBuilder(cw.toByteArray()).build();
	}

	/**
	 * Build a synthetic class with fields but none have ConstantValue attributes.
	 */
	private static JvmClassInfo buildNoConstantsClass() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/NoConstants", null,
				"java/lang/Object", null);

		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"x", "I", null, null
		).visitEnd();

		cw.visitField(
				Opcodes.ACC_PRIVATE,
				"y", "I", null, null
		).visitEnd();

		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"list", "Ljava/util/List;", null, null
		).visitEnd();

		cw.visitEnd();
		return new JvmClassInfoBuilder(cw.toByteArray()).build();
	}

	// ---- Helper to simulate the tool's filtering logic ----

	/**
	 * Replicates the filtering logic of field-get-all-constants tool:
	 * filter fields with non-null default value, optionally include non-static.
	 */
	private static List<FieldMember> filterConstants(JvmClassInfo classInfo, boolean includeNonStatic) {
		return classInfo.getFields().stream()
				.filter(f -> f.getDefaultValue() != null)
				.filter(f -> includeNonStatic || f.hasStaticModifier())
				.collect(Collectors.toList());
	}

	// ---- Tests ----

	@Test
	void defaultBehavior_returnsOnlyStaticConstants() {
		JvmClassInfo classInfo = buildMixedFieldClass();

		List<FieldMember> constants = filterConstants(classInfo, false);

		assertEquals(2, constants.size(), "Should return exactly 2 static constants");

		// Verify the static constants are the expected ones
		List<String> names = constants.stream()
				.map(FieldMember::getName)
				.collect(Collectors.toList());
		assertTrue(names.contains("MAGIC_NUMBER"), "Should include MAGIC_NUMBER");
		assertTrue(names.contains("GREETING"), "Should include GREETING");

		// Verify values
		FieldMember magicNumber = constants.stream()
				.filter(f -> f.getName().equals("MAGIC_NUMBER"))
				.findFirst().orElseThrow();
		assertEquals(42, magicNumber.getDefaultValue());
		assertEquals("int", NavigationToolProvider.getValueTypeName(magicNumber.getDefaultValue()));

		FieldMember greeting = constants.stream()
				.filter(f -> f.getName().equals("GREETING"))
				.findFirst().orElseThrow();
		assertEquals("hello", greeting.getDefaultValue());
		assertEquals("String", NavigationToolProvider.getValueTypeName(greeting.getDefaultValue()));
	}

	@Test
	void includeNonStatic_returnsAllConstants() {
		JvmClassInfo classInfo = buildMixedFieldClass();

		List<FieldMember> constants = filterConstants(classInfo, true);

		assertEquals(3, constants.size(), "Should return 3 constants (2 static + 1 instance)");

		List<String> names = constants.stream()
				.map(FieldMember::getName)
				.collect(Collectors.toList());
		assertTrue(names.contains("MAGIC_NUMBER"), "Should include MAGIC_NUMBER");
		assertTrue(names.contains("GREETING"), "Should include GREETING");
		assertTrue(names.contains("instanceConst"), "Should include instanceConst");

		// Verify instance constant value
		FieldMember instanceConst = constants.stream()
				.filter(f -> f.getName().equals("instanceConst"))
				.findFirst().orElseThrow();
		assertEquals(99L, instanceConst.getDefaultValue());
		assertEquals("long", NavigationToolProvider.getValueTypeName(instanceConst.getDefaultValue()));
	}

	@Test
	void emptyClass_returnsEmptyList() {
		JvmClassInfo classInfo = buildEmptyClass();

		List<FieldMember> constants = filterConstants(classInfo, false);

		assertTrue(constants.isEmpty(), "Empty class should have no constants");
		assertEquals(0, classInfo.getFields().size(), "Empty class should have no fields");
	}

	@Test
	void classWithNoConstants_returnsEmptyList() {
		JvmClassInfo classInfo = buildNoConstantsClass();

		List<FieldMember> constants = filterConstants(classInfo, false);

		assertEquals(0, constants.size(), "Class with no ConstantValue fields should return 0 constants");
		assertEquals(3, classInfo.getFields().size(), "Class should still have 3 fields total");
	}

	@Test
	void classWithNoConstants_includeNonStatic_stillEmpty() {
		JvmClassInfo classInfo = buildNoConstantsClass();

		List<FieldMember> constants = filterConstants(classInfo, true);

		assertEquals(0, constants.size(),
				"Class with no ConstantValue fields should return 0 even with includeNonStatic=true");
	}

	@Test
	void totalFieldCount_matchesAllFields() {
		JvmClassInfo classInfo = buildMixedFieldClass();

		assertEquals(5, classInfo.getFields().size(),
				"Mixed class should have 5 total fields");

		// Verify constant count vs total field count
		List<FieldMember> staticConstants = filterConstants(classInfo, false);
		List<FieldMember> allConstants = filterConstants(classInfo, true);

		assertEquals(2, staticConstants.size());
		assertEquals(3, allConstants.size());
		assertTrue(allConstants.size() < classInfo.getFields().size(),
				"Constants should be a subset of all fields");
	}

	@Test
	void accessFlagsPreserved() {
		JvmClassInfo classInfo = buildMixedFieldClass();
		List<FieldMember> constants = filterConstants(classInfo, true);

		// MAGIC_NUMBER: public static final
		FieldMember magicNumber = constants.stream()
				.filter(f -> f.getName().equals("MAGIC_NUMBER"))
				.findFirst().orElseThrow();
		int expectedStaticAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
		assertEquals(expectedStaticAccess, magicNumber.getAccess(),
				"MAGIC_NUMBER should have public|static|final access flags");

		// instanceConst: public final (NOT static)
		FieldMember instanceConst = constants.stream()
				.filter(f -> f.getName().equals("instanceConst"))
				.findFirst().orElseThrow();
		int expectedInstanceAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
		assertEquals(expectedInstanceAccess, instanceConst.getAccess(),
				"instanceConst should have public|final access flags (no static)");
		assertEquals(0, instanceConst.getAccess() & Opcodes.ACC_STATIC,
				"instanceConst should NOT have ACC_STATIC flag");
	}
}
