package dev.recafmcp.cache;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstructionAnalysisCacheTest {
	@Test
	void sameClassHashReusesParsedInstructionText() {
		InstructionAnalysisCache cache = new InstructionAnalysisCache(new CacheConfig(true, 120, 1000));
		InstructionAnalysisCache.Key key = new InstructionAnalysisCache.Key(
				1L,
				7L,
				"com/example/Test",
				12345
		);

		AtomicInteger parses = new AtomicInteger();
		InstructionAnalysisCache.ClassAnalysis first = cache.getOrLoad(key, () -> {
			parses.incrementAndGet();
			return new InstructionAnalysisCache.ClassAnalysis(
					"com/example/Test",
					List.of(new InstructionAnalysisCache.MethodAnalysis(
							"run",
							"()V",
							List.of(new InstructionAnalysisCache.InstructionText(0, "invokestatic com/example/Util.work ()V")),
							List.of(),
							List.of(),
							List.of("com/example/Util")
					))
			);
		});
		InstructionAnalysisCache.ClassAnalysis second = cache.getOrLoad(key, () -> {
			parses.incrementAndGet();
			return new InstructionAnalysisCache.ClassAnalysis(
					"com/example/Test",
					List.of(new InstructionAnalysisCache.MethodAnalysis(
							"other",
							"()V",
							List.of(new InstructionAnalysisCache.InstructionText(0, "return")),
							List.of(),
							List.of(),
							List.of()
					))
			);
		});

		assertEquals(first, second);
		assertEquals(1, parses.get());
		assertEquals("invokestatic com/example/Util.work ()V",
				second.methods().getFirst().instructions().getFirst().text());
	}
}
