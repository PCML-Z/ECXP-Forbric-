/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.EventBridges;
import net.forbric.api.ForeignType;
import net.forbric.api.GameEventBridge;
import net.forbric.kernel.util.ForbricLog;

/**
 * The vanilla particle provider map, pointed at the live one.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseParticleRepair {
	private MergedBaseParticleRepair() {
	}


	/**
	 * Gives {@code ParticleResources}' vanilla-typed {@code providers} field a live view of the one that is written.
	 *
	 * <p>The same failure class as {@link #routeKeyMappingClickToPopulatedLookup}, at field level. Vanilla declares
	 * {@code providers} as {@code Int2ObjectMap} keyed by particle id; NeoForge 26.2.0.88 RE-TYPES that field to
	 * {@code Map<Identifier, ?>}. Same name, different descriptor is legal, so the merge keeps both and the
	 * surviving {@code <init>} writes only NeoForge's. The vanilla-typed one is null for the life of the process.
	 *
	 * <p>The merge tool sees this pair and correctly declines to delete either — deleting the unwritten one trades
	 * an NPE for a {@code NoSuchFieldError} at the same instruction — and it cannot repair it: its
	 * exclusive-added-field initializer is scoped to fields an ecosystem ADDED, and this is a RE-TYPED VANILLA
	 * field, outside that set by construction. So the repair belongs here, where the whole class is in hand.
	 *
	 * <p>A view rather than a second map, because the two halves have to stay ONE mechanism. fabric-api's
	 * {@code DirectParticleProviderRegistry.register} reads the field DIRECTLY — {@code getfield providers} of the
	 * {@code Int2ObjectMap} descriptor, then {@code PARTICLE_TYPE.getId(type)}, then {@code put(int, provider)} —
	 * so rewriting accessors cannot reach it, and an empty map of its own would swallow the registration and leave
	 * the particle silently unrendered. Writes through the int-keyed face have to be visible to
	 * {@code ParticleEngine.makeParticle}, which reads the {@code Identifier}-keyed one.
	 *
	 * <p>The insert goes immediately after {@code <init>}'s write of the live map and BEFORE its
	 * {@code registerProviders()} call, not before {@code RETURN}: fabric-api's {@code ParticleResourcesMixin}
	 * injects at {@code registerProviders}'s RETURN, so a repair placed at the end of the constructor is still too
	 * late and reproduces the crash while looking correct.
	 *
	 * <p>It also repoints {@code getProvider} at the live map. MinecraftForge added {@code providersByName} and
	 * filled it from its own {@code register}, which the merge dropped — so the merged class initializes it, from
	 * a synthetic default AFTER {@code registerProviders} has already run, and nothing ever puts anything in it.
	 * The two maps held the same thing by construction (both keyed {@code getKey(type)}, same descriptor), so this
	 * is a rename.
	 */
	static boolean giveTheVanillaParticleMapAViewOfTheLiveOne(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.PARTICLE_RESOURCES.equals(node.name)) return false;
		// Only when the merge actually split it. A single-ecosystem or rebuilt base is already coherent.
		if (!MergedBaseAsm.hasField(node, "providers", ForbricMergedBaseCompatTransformer.NAME_KEYED) || !MergedBaseAsm.hasField(node, "providers", ForbricMergedBaseCompatTransformer.ID_KEYED)) return false;

		MethodNode init = MergedBaseAsm.findMethod(node, "<init>", "()V");
		if (init == null) return false;

		FieldInsnNode anchor = null;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
						|| !node.name.equals(field.owner) || !"providers".equals(field.name)) {
					continue;
				}
				if (ForbricMergedBaseCompatTransformer.ID_KEYED.equals(field.desc)) {
					// Already written by something — a rebuilt base, or this pass having run before. Stand down:
					// this is also what makes the pass idempotent.
					return false;
				}
				if (!ForbricMergedBaseCompatTransformer.NAME_KEYED.equals(field.desc)) continue;
				if (anchor != null || method != init) {
					ForbricLog.warn("[Forbric/MergedBaseCompat] ParticleResources writes its provider map more than "
							+ "once, or outside <init> — the view below would capture a map that is later replaced, "
							+ "so it is not installed");
					return false;
				}
				anchor = field;
			}
		}
		if (anchor == null) return false;

		InsnList view = new InsnList();
		view.add(new VarInsnNode(Opcodes.ALOAD, 0));
		view.add(new VarInsnNode(Opcodes.ALOAD, 0));
		view.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "providers", ForbricMergedBaseCompatTransformer.NAME_KEYED));
		view.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_PARTICLES, "intKeyedView",
				"(Ljava/util/Map;)Ljava/lang/Object;", false));
		view.add(new TypeInsnNode(Opcodes.CHECKCAST, ForbricMergedBaseCompatTransformer.ID_KEYED.substring(1, ForbricMergedBaseCompatTransformer.ID_KEYED.length() - 1)));
		view.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "providers", ForbricMergedBaseCompatTransformer.ID_KEYED));
		init.instructions.insert(anchor, view);
		init.maxStack = Math.max(init.maxStack, 2);

		MergedBaseKeyRepair.routeGetProviderAtTheLiveMap(node);

		ForbricLog.warn("[Forbric/MergedBaseCompat] ParticleResources had two `providers` fields and only one was "
				+ "ever written — the vanilla-typed one, which fabric-api's particle registry reads directly, was "
				+ "null, so any mod using that API crashed inside Minecraft.<init>. It is now a live view of the "
				+ "map that IS written");
		return true;
	}
}
