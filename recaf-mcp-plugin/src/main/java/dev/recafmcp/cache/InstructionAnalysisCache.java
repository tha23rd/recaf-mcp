package dev.recafmcp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.util.BlwUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared cache for immutable instruction-analysis DTOs derived from JVM classes.
 */
public final class InstructionAnalysisCache {
	private final boolean enabled;
	private final Cache<Key, ClassAnalysis> cache;

	public InstructionAnalysisCache(CacheConfig config) {
		this.enabled = config.enabled();
		this.cache = Caffeine.newBuilder()
				.maximumSize(config.maxEntries())
				.expireAfterWrite(Duration.ofSeconds(config.ttlSeconds()))
				.build();
	}

	public ClassAnalysis getOrLoad(Key key, Supplier<ClassAnalysis> loader) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(loader, "loader");
		if (!enabled) {
			return loader.get();
		}
		return cache.get(key, ignored -> loader.get());
	}

	public Key keyFor(long workspaceIdentity, long workspaceRevision, JvmClassInfo classInfo) {
		Objects.requireNonNull(classInfo, "classInfo");
		return new Key(
				workspaceIdentity,
				workspaceRevision,
				classInfo.getName(),
				Arrays.hashCode(classInfo.getBytecode())
		);
	}

	public static ClassAnalysis analyzeClass(JvmClassInfo classInfo) {
		Objects.requireNonNull(classInfo, "classInfo");

		ClassReader reader = classInfo.getClassReader();
		ClassNode classNode = new ClassNode();
		reader.accept(classNode, ClassReader.SKIP_FRAMES);

		List<MethodAnalysis> methods = new ArrayList<>(classNode.methods.size());
		for (MethodNode methodNode : classNode.methods) {
			methods.add(analyzeMethod(methodNode));
		}
		return new ClassAnalysis(classInfo.getName(), methods);
	}

	private static MethodAnalysis analyzeMethod(MethodNode methodNode) {
		List<InstructionText> instructions = new ArrayList<>();
		List<MethodReference> methodReferences = new ArrayList<>();
		List<FieldReference> fieldReferences = new ArrayList<>();
		LinkedHashSet<String> typeReferences = new LinkedHashSet<>();

		InsnList methodInstructions = methodNode.instructions;
		if (methodInstructions != null) {
			String methodContext = methodNode.name + methodNode.desc;
			for (int i = 0; i < methodInstructions.size(); i++) {
				AbstractInsnNode insn = methodInstructions.get(i);
				if (insn.getOpcode() >= 0) {
					instructions.add(new InstructionText(i, BlwUtil.toString(insn)));
				}
				switch (insn) {
					case MethodInsnNode min -> {
						methodReferences.add(MethodReference.forMethodInvoke(
								methodContext,
								min.owner,
								min.name,
								min.desc
						));
						typeReferences.add(min.owner);
					}
					case FieldInsnNode fin -> {
						fieldReferences.add(new FieldReference(
								methodContext,
								fin.owner,
								fin.name,
								fin.desc
						));
						typeReferences.add(fin.owner);
					}
					case InvokeDynamicInsnNode indy -> {
						List<String> bootstrapArgs = new ArrayList<>();
						if (indy.bsmArgs != null) {
							for (Object arg : indy.bsmArgs) {
								bootstrapArgs.add(arg.toString());
							}
						}
						methodReferences.add(MethodReference.forInvokeDynamic(
								methodContext,
								indy.bsm.getOwner(),
								indy.bsm.getName(),
								indy.bsm.getDesc(),
								indy.name,
								indy.desc,
								bootstrapArgs
						));
						typeReferences.add(indy.bsm.getOwner());
					}
					case TypeInsnNode tin -> typeReferences.add(tin.desc);
					default -> {
						// Other instruction types don't produce xrefs.
					}
				}
			}
		}

		return new MethodAnalysis(
				methodNode.name,
				methodNode.desc,
				instructions,
				methodReferences,
				fieldReferences,
				List.copyOf(typeReferences)
		);
	}

	public record Key(
			long workspaceIdentity,
			long workspaceRevision,
			String className,
			int classBytecodeHash
	) {
	}

	public record ClassAnalysis(
			String className,
			List<MethodAnalysis> methods
	) {
		public ClassAnalysis {
			Objects.requireNonNull(className, "className");
			Objects.requireNonNull(methods, "methods");
			methods = List.copyOf(methods);
		}
	}

	public record MethodAnalysis(
			String methodName,
			String methodDescriptor,
			List<InstructionText> instructions,
			List<MethodReference> methodReferences,
			List<FieldReference> fieldReferences,
			List<String> typeReferences
	) {
		public MethodAnalysis {
			Objects.requireNonNull(methodName, "methodName");
			Objects.requireNonNull(methodDescriptor, "methodDescriptor");
			Objects.requireNonNull(instructions, "instructions");
			Objects.requireNonNull(methodReferences, "methodReferences");
			Objects.requireNonNull(fieldReferences, "fieldReferences");
			Objects.requireNonNull(typeReferences, "typeReferences");
			instructions = List.copyOf(instructions);
			methodReferences = List.copyOf(methodReferences);
			fieldReferences = List.copyOf(fieldReferences);
			typeReferences = List.copyOf(typeReferences);
		}
	}

	public record InstructionText(
			int index,
			String text
	) {
		public InstructionText {
			Objects.requireNonNull(text, "text");
		}
	}

	public record MethodReference(
			String fromMethod,
			String type,
			String targetOwner,
			String targetName,
			String targetDescriptor,
			String bootstrapOwner,
			String bootstrapName,
			String bootstrapDescriptor,
			String callName,
			String callDescriptor,
			List<String> bootstrapArgs
	) {
		public MethodReference {
			Objects.requireNonNull(fromMethod, "fromMethod");
			Objects.requireNonNull(type, "type");
			bootstrapArgs = bootstrapArgs == null ? List.of() : List.copyOf(bootstrapArgs);
		}

		public static MethodReference forMethodInvoke(String fromMethod,
		                                              String targetOwner,
		                                              String targetName,
		                                              String targetDescriptor) {
			return new MethodReference(
					fromMethod,
					"method",
					targetOwner,
					targetName,
					targetDescriptor,
					null,
					null,
					null,
					null,
					null,
					List.of()
			);
		}

		public static MethodReference forInvokeDynamic(String fromMethod,
		                                               String bootstrapOwner,
		                                               String bootstrapName,
		                                               String bootstrapDescriptor,
		                                               String callName,
		                                               String callDescriptor,
		                                               List<String> bootstrapArgs) {
			return new MethodReference(
					fromMethod,
					"invokedynamic",
					null,
					null,
					null,
					bootstrapOwner,
					bootstrapName,
					bootstrapDescriptor,
					callName,
					callDescriptor,
					bootstrapArgs
			);
		}
	}

	public record FieldReference(
			String fromMethod,
			String targetOwner,
			String targetName,
			String targetDescriptor
	) {
		public FieldReference {
			Objects.requireNonNull(fromMethod, "fromMethod");
			Objects.requireNonNull(targetOwner, "targetOwner");
			Objects.requireNonNull(targetName, "targetName");
			Objects.requireNonNull(targetDescriptor, "targetDescriptor");
		}
	}
}
