package dev.recafmcp.providers;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.member.FieldMember;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the field-get-value tool logic: field lookup by name and by
 * name+descriptor, ConstantValue extraction, and modifier detection
 * via {@code hasStaticModifier()} / {@code hasFinalModifier()}.
 * <p>
 * Uses ASM-generated synthetic classes parsed through Recaf's
 * {@link JvmClassInfoBuilder} to exercise the exact same APIs used
 * by {@link NavigationToolProvider#registerFieldGetValue()}.
 */
class FieldGetValueTest {

	// ---- Helper: build a synthetic class with various field types ----

	/**
	 * Create a synthetic class with fields covering all the interesting cases:
	 * <ul>
	 *   <li>{@code static final int INT_CONST = 42} (has ConstantValue)</li>
	 *   <li>{@code static final String STR_CONST = "hello"} (has ConstantValue)</li>
	 *   <li>{@code static final long LONG_CONST = 123456789L} (has ConstantValue)</li>
	 *   <li>{@code static final float FLOAT_CONST = 2.5f} (has ConstantValue)</li>
	 *   <li>{@code static final double DOUBLE_CONST = 3.14159} (has ConstantValue)</li>
	 *   <li>{@code static int staticNonFinal} (no ConstantValue, clinit-initialized)</li>
	 *   <li>{@code int instanceField} (instance field, no ConstantValue)</li>
	 *   <li>{@code static final Object COMPUTED} (static final, but no ConstantValue — initialized in clinit)</li>
	 * </ul>
	 */
	private static JvmClassInfo buildSyntheticClass() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/TestFields", null,
				"java/lang/Object", null);

		// static final int with ConstantValue
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"INT_CONST", "I", null, Integer.valueOf(42)
		).visitEnd();

		// static final String with ConstantValue
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"STR_CONST", "Ljava/lang/String;", null, "hello"
		).visitEnd();

		// static final long with ConstantValue
		cw.visitField(
				Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"LONG_CONST", "J", null, Long.valueOf(123456789L)
		).visitEnd();

		// static final float with ConstantValue
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"FLOAT_CONST", "F", null, Float.valueOf(2.5f)
		).visitEnd();

		// static final double with ConstantValue
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"DOUBLE_CONST", "D", null, Double.valueOf(3.14159)
		).visitEnd();

		// static non-final (no ConstantValue)
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"staticNonFinal", "I", null, null
		).visitEnd();

		// instance field (no ConstantValue)
		cw.visitField(
				Opcodes.ACC_PRIVATE,
				"instanceField", "I", null, null
		).visitEnd();

		// static final with non-primitive type — no ConstantValue attribute
		// (only primitives and String get ConstantValue in JVM spec)
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"COMPUTED", "Ljava/lang/Object;", null, null
		).visitEnd();

		cw.visitEnd();
		return new JvmClassInfoBuilder(cw.toByteArray()).build();
	}

	/**
	 * Build a class with two fields that share the same name but have different descriptors,
	 * to test descriptor-based disambiguation.
	 */
	private static JvmClassInfo buildAmbiguousFieldClass() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Ambiguous", null,
				"java/lang/Object", null);

		// Two fields named "value" with different types
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"value", "I", null, Integer.valueOf(100)
		).visitEnd();

		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"value", "Ljava/lang/String;", null, "hundred"
		).visitEnd();

		cw.visitEnd();
		return new JvmClassInfoBuilder(cw.toByteArray()).build();
	}

	// ---- Tests: static final with ConstantValue ----

	@Test
	void staticFinalInt_hasConstantValue() {
		JvmClassInfo classInfo = buildSyntheticClass();
		FieldMember field = classInfo.getFirstDeclaredFieldByName("INT_CONST");

		assertNotNull(field);
		assertTrue(field.hasStaticModifier());
		assertTrue(field.hasFinalModifier());

		Object value = field.getDefaultValue();
		assertNotNull(value, "static final int should have a ConstantValue");
		assertInstanceOf(Integer.class, value);
		assertEquals(42, value);
		assertEquals("int", NavigationToolProvider.getValueTypeName(value));
	}

	@Test
	void staticFinalString_hasConstantValue() {
		JvmClassInfo classInfo = buildSyntheticClass();
		FieldMember field = classInfo.getFirstDeclaredFieldByName("STR_CONST");

		assertNotNull(field);
		assertTrue(field.hasStaticModifier());
		assertTrue(field.hasFinalModifier());

		Object value = field.getDefaultValue();
		assertNotNull(value, "static final String should have a ConstantValue");
		assertInstanceOf(String.class, value);
		assertEquals("hello", value);
		assertEquals("String", NavigationToolProvider.getValueTypeName(value));
	}

	@Test
	void staticFinalLong_hasConstantValue() {
		JvmClassInfo classInfo = buildSyntheticClass();
		FieldMember field = classInfo.getFirstDeclaredFieldByName("LONG_CONST");

		assertNotNull(field);
		Object value = field.getDefaultValue();
		assertNotNull(value);
		assertInstanceOf(Long.class, value);
		assertEquals(123456789L, value);
		assertEquals("long", NavigationToolProvider.getValueTypeName(value));
	}

	@Test
	void staticFinalFloat_hasConstantValue() {
		JvmClassInfo classInfo = buildSyntheticClass();
		FieldMember field = classInfo.getFirstDeclaredFieldByName("FLOAT_CONST");

		assertNotNull(field);
		Object value = field.getDefaultValue();
		assertNotNull(value);
		assertInstanceOf(Float.class, value);
		assertEquals(2.5f, value);
		assertEquals("float", NavigationToolProvider.getValueTypeName(value));
	}

	@Test
	void staticFinalDouble_hasConstantValue() {
		JvmClassInfo classInfo = buildSyntheticClass();
		FieldMember field = classInfo.getFirstDeclaredFieldByName("DOUBLE_CONST");

		assertNotNull(field);
		Object value = field.getDefaultValue();
		assertNotNull(value);
		assertInstanceOf(Double.class, value);
		assertEquals(3.14159, value);
		assertEquals("double", NavigationToolProvider.getValueTypeName(value));
	}

	// ---- Tests: fields without ConstantValue ----

	@Test
	void staticNonFinal_noConstantValue() {
		JvmClassInfo classInfo = buildSyntheticClass();
		FieldMember field = classInfo.getFirstDeclaredFieldByName("staticNonFinal");

		assertNotNull(field);
		assertTrue(field.hasStaticModifier(), "should be static");
		assertFalse(field.hasFinalModifier(), "should not be final");
		assertNull(field.getDefaultValue(), "non-final static should have no ConstantValue");
	}

	@Test
	void instanceField_noConstantValue() {
		JvmClassInfo classInfo = buildSyntheticClass();
		FieldMember field = classInfo.getFirstDeclaredFieldByName("instanceField");

		assertNotNull(field);
		assertFalse(field.hasStaticModifier(), "should not be static");
		assertFalse(field.hasFinalModifier(), "should not be final");
		assertNull(field.getDefaultValue(), "instance field should have no ConstantValue");
	}

	@Test
	void staticFinalObject_noConstantValue() {
		JvmClassInfo classInfo = buildSyntheticClass();
		FieldMember field = classInfo.getFirstDeclaredFieldByName("COMPUTED");

		assertNotNull(field);
		assertTrue(field.hasStaticModifier(), "should be static");
		assertTrue(field.hasFinalModifier(), "should be final");
		assertNull(field.getDefaultValue(),
				"static final Object should NOT have a ConstantValue (only primitives and String do)");
	}

	// ---- Tests: field lookup by name vs name+descriptor ----

	@Test
	void lookupByNameOnly_returnsFirstMatch() {
		JvmClassInfo classInfo = buildAmbiguousFieldClass();

		// getFirstDeclaredFieldByName returns the first match
		FieldMember field = classInfo.getFirstDeclaredFieldByName("value");
		assertNotNull(field, "should find field by name");
		assertEquals("value", field.getName());
		// The first field declared was the int
		assertEquals("I", field.getDescriptor());
	}

	@Test
	void lookupByNameAndDescriptor_exactMatch() {
		JvmClassInfo classInfo = buildAmbiguousFieldClass();

		// Look up the int variant
		FieldMember intField = classInfo.getDeclaredField("value", "I");
		assertNotNull(intField);
		assertEquals("I", intField.getDescriptor());
		assertEquals(100, intField.getDefaultValue());

		// Look up the String variant
		FieldMember strField = classInfo.getDeclaredField("value", "Ljava/lang/String;");
		assertNotNull(strField);
		assertEquals("Ljava/lang/String;", strField.getDescriptor());
		assertEquals("hundred", strField.getDefaultValue());
	}

	@Test
	void lookupByNameAndWrongDescriptor_returnsNull() {
		JvmClassInfo classInfo = buildAmbiguousFieldClass();

		FieldMember field = classInfo.getDeclaredField("value", "D");
		assertNull(field, "should not find field with wrong descriptor");
	}

	// ---- Tests: error cases ----

	@Test
	void lookupNonexistentField_returnsNull() {
		JvmClassInfo classInfo = buildSyntheticClass();

		FieldMember field = classInfo.getFirstDeclaredFieldByName("doesNotExist");
		assertNull(field, "non-existent field should return null");
	}

	@Test
	void lookupNonexistentFieldByDescriptor_returnsNull() {
		JvmClassInfo classInfo = buildSyntheticClass();

		FieldMember field = classInfo.getDeclaredField("doesNotExist", "I");
		assertNull(field, "non-existent field should return null");
	}

	// ---- Tests: field descriptor and access ----

	@Test
	void fieldDescriptor_isCorrect() {
		JvmClassInfo classInfo = buildSyntheticClass();

		assertEquals("I", classInfo.getFirstDeclaredFieldByName("INT_CONST").getDescriptor());
		assertEquals("Ljava/lang/String;", classInfo.getFirstDeclaredFieldByName("STR_CONST").getDescriptor());
		assertEquals("J", classInfo.getFirstDeclaredFieldByName("LONG_CONST").getDescriptor());
		assertEquals("F", classInfo.getFirstDeclaredFieldByName("FLOAT_CONST").getDescriptor());
		assertEquals("D", classInfo.getFirstDeclaredFieldByName("DOUBLE_CONST").getDescriptor());
	}

	@Test
	void fieldAccess_reflectsModifiers() {
		JvmClassInfo classInfo = buildSyntheticClass();

		FieldMember publicStaticFinal = classInfo.getFirstDeclaredFieldByName("INT_CONST");
		assertTrue(publicStaticFinal.hasPublicModifier());
		assertTrue(publicStaticFinal.hasStaticModifier());
		assertTrue(publicStaticFinal.hasFinalModifier());

		FieldMember privateStaticFinal = classInfo.getFirstDeclaredFieldByName("LONG_CONST");
		assertTrue(privateStaticFinal.hasPrivateModifier());
		assertTrue(privateStaticFinal.hasStaticModifier());
		assertTrue(privateStaticFinal.hasFinalModifier());

		FieldMember privateInstance = classInfo.getFirstDeclaredFieldByName("instanceField");
		assertTrue(privateInstance.hasPrivateModifier());
		assertFalse(privateInstance.hasStaticModifier());
		assertFalse(privateInstance.hasFinalModifier());
	}
}
