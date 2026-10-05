/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.config.ForbricConfig;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Preserve vanilla identity semantics for SoundEvent records inside Forge's registry carrier. */
public final class SoundRegistryIdentityInjector implements ClassTransformer {
	public static final String TARGET = "net.minecraftforge.registries.ForgeRegistry";
	private static final String HOOK = "net/forbric/kernel/runtime/IdentityValueBiMap";
	private static final Set<String> INDEXES = Set.of("ids", "names", "keys", "owners");

	@Override public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"distinct sound events with equal contents cannot register under different names"));
	}

	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(name) || !ForbricConfig.get().flag("forbric.soundRegistryIdentity", "runtime.soundRegistryIdentity", true)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!method.name.equals("<init>") || !method.desc.equals("(Lnet/minecraftforge/registries/RegistryManager;Lnet/minecraft/resources/Identifier;Lnet/minecraftforge/registries/RegistryBuilder;)V")) continue;
			var fields = new java.util.ArrayList<FieldInsnNode>();
			for (AbstractInsnNode instruction : method.instructions.toArray()) {
				if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
						|| !field.owner.equals(node.name) || !(INDEXES.contains(field.name) || field.name.equals("delegatesByValue"))) continue;
				AbstractInsnNode previous = field.getPrevious();
				while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
				if (previous instanceof MethodInsnNode call && call.owner.equals(HOOK)) return bytes;
				if (!field.desc.equals(field.name.equals("delegatesByValue") ? "Ljava/util/Map;" : "Lcom/google/common/collect/BiMap;")) return bytes;
				fields.add(field);
			}
			if (fields.size() != 5 || fields.stream().map(f -> f.name).distinct().count() != 5) return bytes;
			for (FieldInsnNode field : fields) {
				// The native empty map is already on the stack. Replace it before any registry entries are added.
				InsnList hook = new InsnList();
				hook.add(new InsnNode(Opcodes.POP));
				hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
				boolean delegates = field.name.equals("delegatesByValue");
				hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
						delegates ? "delegatesForRegistry" : "forRegistry",
						"(Ljava/lang/Object;)L" + (delegates ? "java/util/Map" : "com/google/common/collect/BiMap") + ";", false));
				method.instructions.insertBefore(field, hook);
				changed = true;
			}
		}
		if (!changed) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
