package dev.recafmcp.providers;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.member.FieldMember;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for field default value (ConstantValue attribute) support in
 * NavigationToolProvider, including the getValueTypeName() helper and
 * verification that ASM-generated fields with ConstantValue attributes
 * are correctly read by Recaf's JvmClassInfoBuilder.
 */
class FieldDefaultValueTest {

	// ---- getValueTypeName tests ----

	@Test
	void getValueTypeName_integer() {
		assertEquals("int", NavigationToolProvider.getValueTypeName(Integer.valueOf(42)));
	}

	@Test
	void getValueTypeName_long() {
		assertEquals("long", NavigationToolProvider.getValueTypeName(Long.valueOf(999999L)));
	}

	@Test
	void getValueTypeName_float() {
		assertEquals("float", NavigationToolProvider.getValueTypeName(Float.valueOf(1.5f)));
	}

	@Test
	void getValueTypeName_double() {
		assertEquals("double", NavigationToolProvider.getValueTypeName(Double.valueOf(3.14)));
	}

	@Test
	void getValueTypeName_string() {
		assertEquals("String", NavigationToolProvider.getValueTypeName("hello"));
	}

	// ---- ConstantValue attribute via ASM + Recaf JvmClassInfoBuilder ----

	/**
	 * Create a synthetic class using ASM ClassWriter with fields that have
	 * ConstantValue attributes, parse it with Recaf's JvmClassInfoBuilder,
	 * and verify getDefaultValue() returns the expected values.
	 */
	@Test
	void constantValueAttributes_readCorrectly() {
		// Build a synthetic class with various constant fields
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Constants", null,
				"java/lang/Object", null);

		// static final int MAX = 1024
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"MAX", "I", null, Integer.valueOf(1024)
		).visitEnd();

		// static final String NAME = "test"
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"NAME", "Ljava/lang/String;", null, "test"
		).visitEnd();

		// static final long BIG = 999999L
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"BIG", "J", null, Long.valueOf(999999L)
		).visitEnd();

		// static final double PI = 3.14
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"PI", "D", null, Double.valueOf(3.14)
		).visitEnd();

		// static int noConstant (no ConstantValue attribute)
		cw.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"noConstant", "I", null, null
		).visitEnd();

		cw.visitEnd();
		byte[] bytecode = cw.toByteArray();

		// Parse with Recaf's JvmClassInfoBuilder
		JvmClassInfo classInfo = new JvmClassInfoBuilder(bytecode).build();
		assertNotNull(classInfo);
		assertEquals(5, classInfo.getFields().size());

		// Verify int constant
		FieldMember maxField = classInfo.getFirstDeclaredFieldByName("MAX");
		assertNotNull(maxField, "Field 'MAX' should exist");
		assertNotNull(maxField.getDefaultValue(), "MAX should have a default value");
		assertInstanceOf(Integer.class, maxField.getDefaultValue());
		assertEquals(1024, maxField.getDefaultValue());

		// Verify String constant
		FieldMember nameField = classInfo.getFirstDeclaredFieldByName("NAME");
		assertNotNull(nameField, "Field 'NAME' should exist");
		assertNotNull(nameField.getDefaultValue(), "NAME should have a default value");
		assertInstanceOf(String.class, nameField.getDefaultValue());
		assertEquals("test", nameField.getDefaultValue());

		// Verify long constant
		FieldMember bigField = classInfo.getFirstDeclaredFieldByName("BIG");
		assertNotNull(bigField, "Field 'BIG' should exist");
		assertNotNull(bigField.getDefaultValue(), "BIG should have a default value");
		assertInstanceOf(Long.class, bigField.getDefaultValue());
		assertEquals(999999L, bigField.getDefaultValue());

		// Verify double constant
		FieldMember piField = classInfo.getFirstDeclaredFieldByName("PI");
		assertNotNull(piField, "Field 'PI' should exist");
		assertNotNull(piField.getDefaultValue(), "PI should have a default value");
		assertInstanceOf(Double.class, piField.getDefaultValue());
		assertEquals(3.14, piField.getDefaultValue());

		// Verify non-constant field returns null
		FieldMember noConstField = classInfo.getFirstDeclaredFieldByName("noConstant");
		assertNotNull(noConstField, "Field 'noConstant' should exist");
		assertNull(noConstField.getDefaultValue(),
				"noConstant should NOT have a default value");
	}

	/**
	 * Verify that getValueTypeName returns correct types for all values
	 * extracted from a real ConstantValue attribute round-trip.
	 */
	@Test
	void getValueTypeName_matchesConstantValueTypes() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/TypeCheck", null,
				"java/lang/Object", null);

		cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"i", "I", null, Integer.valueOf(1)).visitEnd();
		cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"l", "J", null, Long.valueOf(2L)).visitEnd();
		cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"f", "F", null, Float.valueOf(3.0f)).visitEnd();
		cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"d", "D", null, Double.valueOf(4.0)).visitEnd();
		cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"s", "Ljava/lang/String;", null, "five").visitEnd();

		cw.visitEnd();
		JvmClassInfo classInfo = new JvmClassInfoBuilder(cw.toByteArray()).build();

		assertEquals("int", NavigationToolProvider.getValueTypeName(
				classInfo.getFirstDeclaredFieldByName("i").getDefaultValue()));
		assertEquals("long", NavigationToolProvider.getValueTypeName(
				classInfo.getFirstDeclaredFieldByName("l").getDefaultValue()));
		assertEquals("float", NavigationToolProvider.getValueTypeName(
				classInfo.getFirstDeclaredFieldByName("f").getDefaultValue()));
		assertEquals("double", NavigationToolProvider.getValueTypeName(
				classInfo.getFirstDeclaredFieldByName("d").getDefaultValue()));
		assertEquals("String", NavigationToolProvider.getValueTypeName(
				classInfo.getFirstDeclaredFieldByName("s").getDefaultValue()));
	}
}
