package dev.recafmcp.providers;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CacheKeyPartEncodingTest {
	@Test
	void searchProviderNullAndLiteralNullSentinelAreDistinct() throws Exception {
		String encodedNull = invokeCacheKeyPart(SearchToolProvider.class, null);
		String encodedLiteral = invokeCacheKeyPart(SearchToolProvider.class, "<null>");
		assertNotEquals(encodedNull, encodedLiteral);
	}

	@Test
	void xrefProviderNullAndLiteralNullSentinelAreDistinct() throws Exception {
		String encodedNull = invokeCacheKeyPart(XRefToolProvider.class, null);
		String encodedLiteral = invokeCacheKeyPart(XRefToolProvider.class, "<null>");
		assertNotEquals(encodedNull, encodedLiteral);
	}

	private static String invokeCacheKeyPart(Class<?> providerType, String input) throws Exception {
		Method method = providerType.getDeclaredMethod("cacheKeyPart", String.class);
		method.setAccessible(true);
		return (String) method.invoke(null, input);
	}
}
