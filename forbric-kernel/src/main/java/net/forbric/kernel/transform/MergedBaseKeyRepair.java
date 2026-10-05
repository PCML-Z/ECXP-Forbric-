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
 * KeyMapping, so a MinecraftForge mod and the vanilla map talk to the same lookup.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseKeyRepair {
	private MergedBaseKeyRepair() {
	}


	static boolean addMissingForgeKeyMappingLookupInitializer(ClassNode node) {
		if (!"net/minecraft/client/KeyMapping".equals(node.name)) return false;
		String forgeLookup = "Lnet/minecraftforge/client/settings/KeyMappingLookup;";
		if (!MergedBaseAsm.hasField(node, "MAP", forgeLookup) || MergedBaseAsm.initializesStaticField(node, "MAP", forgeLookup)) return false;

		MethodNode clinit = MergedBaseAsm.findMethod(node, "<clinit>", "()V");
		if (clinit == null) {
			clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
			clinit.instructions.add(new InsnNode(Opcodes.RETURN));
			clinit.maxLocals = 0;
			node.methods.add(clinit);
		}

		boolean inserted = false;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			clinit.instructions.insertBefore(insn, new TypeInsnNode(Opcodes.NEW,
					ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE)));
			clinit.instructions.insertBefore(insn, new InsnNode(Opcodes.DUP));
			clinit.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESPECIAL,
					ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE), "<init>", "()V", false));
			clinit.instructions.insertBefore(insn, new FieldInsnNode(Opcodes.PUTSTATIC,
					"net/minecraft/client/KeyMapping", "MAP", forgeLookup));
			inserted = true;
		}
		if (!inserted) return false;

		clinit.maxStack = Math.max(clinit.maxStack, 2);
		ForbricLog.warn("[Forbric/MergedBaseCompat] initialized Forge KeyMapping lookup on merged client base");
		return true;
	}


	/**
	 * Points {@code KeyMapping.click} at the key lookup that registration actually populates.
	 *
	 * <p>The byte-merge left {@code KeyMapping} with TWO static fields both named {@code MAP} — NeoForge's
	 * {@code KeyMappingLookup} and MinecraftForge's (same name, different descriptor: legal in bytecode, unwritable
	 * in Java source). Every WRITE goes to the NeoForge one ({@code registerMapping}, the constructors,
	 * {@code setKeyModifierAndCode}, {@code resetMapping}), and {@code <clinit>} only ever assigned that one. The
	 * merge also kept BOTH {@code forAllKeyMappings} overloads, and they READ different maps: the 3-arg one — used
	 * by {@code KeyMapping.set}, which drives {@code isDown} — reads NeoForge's, while the 2-arg one, whose single
	 * caller is {@code KeyMapping.click} (it drives {@code clickCount}), reads MinecraftForge's.
	 *
	 * <p>So the Forge lookup is permanently EMPTY and {@code click} matches nothing: {@code clickCount} never
	 * increments and {@code consumeClick()} is forever false. That kills every {@code consumeClick}-driven key for
	 * vanilla AND every mod — inventory (E), chat (T), command ({@code /}), drop (Q) — while {@code isDown} keys
	 * (WASD, sneak, attack) keep working, because {@code set} reads the populated map. ESC still opens the pause
	 * menu, because that is a direct key-code check in {@code KeyboardHandler}, not a {@code KeyMapping} — which is
	 * exactly the "ESC pauses but E does nothing" shape this presents as.
	 *
	 * <p>Both {@code getAll(InputConstants$Key)} overloads return {@code List<KeyMapping>}, so redirecting the field
	 * read and the call is descriptor-identical. {@link #addMissingForgeKeyMappingLookupInitializer} still runs, so
	 * the Forge lookup stays non-null for any Forge code that reaches for it directly.
	 */
	static boolean routeKeyMappingClickToPopulatedLookup(ClassNode node) {
		if (!"net/minecraft/client/KeyMapping".equals(node.name)) return false;

		String forgeLookup = "Lnet/minecraftforge/client/settings/KeyMappingLookup;";
		String neoLookup = "Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;";
		// Only meaningful when the merge actually produced BOTH lookups; a single-ecosystem base is already coherent.
		if (!MergedBaseAsm.hasField(node, "MAP", forgeLookup) || !MergedBaseAsm.hasField(node, "MAP", neoLookup)) return false;

		MethodNode lookup = MergedBaseAsm.findMethod(node, "forAllKeyMappings",
				"(Lcom/mojang/blaze3d/platform/InputConstants$Key;Ljava/util/function/Consumer;)V");
		if (lookup == null) return false;

		boolean changed = false;
		for (AbstractInsnNode insn = lookup.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode field
					&& "MAP".equals(field.name) && forgeLookup.equals(field.desc)) {
				field.desc = neoLookup;
				changed = true;
			} else if (insn instanceof MethodInsnNode call
					&& ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE).equals(call.owner)
					&& "getAll".equals(call.name)) {
				call.owner = ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.NEOFORGE);
				changed = true;
			}
		}
		if (!changed) return false;

		ForbricLog.warn("[Forbric/MergedBaseCompat] routed KeyMapping.click to the populated (NeoForge) key lookup "
				+ "— the merge left it reading the Forge-side MAP, which is never written, so every consumeClick key "
				+ "(inventory/chat/command/drop) was dead");
		return true;
	}


	/**
	 * Points {@code getProvider} at the live map instead of the empty {@code providersByName}.
	 *
	 * <p>Only when nothing outside {@code <init>} writes {@code providersByName}: if a base ever keeps
	 * MinecraftForge's {@code register}, the field is live again and must be left alone. The declaration stays
	 * either way — removing it would break any access widener that named it, for no gain.
	 */
	static void routeGetProviderAtTheLiveMap(ClassNode node) {
		if (!MergedBaseAsm.hasField(node, "providersByName", ForbricMergedBaseCompatTransformer.NAME_KEYED)) return;
		for (MethodNode method : node.methods) {
			if ("<init>".equals(method.name)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
						&& node.name.equals(field.owner) && "providersByName".equals(field.name)) {
					return; // a live producer survived; nothing to reroute
				}
			}
		}
		MethodNode getProvider = MergedBaseAsm.findMethod(node, "getProvider",
				"(Lnet/minecraft/core/particles/ParticleType;)Lnet/minecraft/client/particle/ParticleProvider;");
		if (getProvider == null) return;
		for (AbstractInsnNode insn = getProvider.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
					&& node.name.equals(field.owner) && "providersByName".equals(field.name)
					&& ForbricMergedBaseCompatTransformer.NAME_KEYED.equals(field.desc)) {
				field.name = "providers";
			}
		}
	}


	/**
	 * Gives {@code KeyMapping} back the MinecraftForge-typed accessors the merge dropped, and makes them mean
	 * something.
	 *
	 * <p>Two halves of one defect, both found by onekeyminer dying in its client setup with
	 * {@code AbstractMethodError: KeyMapping.setKeyConflictContext(…IKeyConflictContext) is abstract}.
	 *
	 * <p>First: the merged class kept NeoForge's accessors and MinecraftForge's constructors and FIELDS, but not
	 * MinecraftForge's accessors — an abstract method has no body for the splice to take. Any Forge mod that
	 * configures a keybinding therefore fails, and it fails in a class initializer, so the mod loses everything
	 * downstream of it.
	 *
	 * <p>Second, and the reason re-adding the methods over the MinecraftForge fields would have been worse than
	 * the crash: those fields are DEAD. Every live consumer reads NeoForge's — {@code same()} resolves conflicts
	 * through the NeoForge-typed {@code getKeyConflictContext}, and {@code isActiveAndMatches} /
	 * {@code setToDefault} / {@code isConflictContextAndModifierActive} all delegate into
	 * {@code IKeyMappingExtension}. A setter that wrote the MinecraftForge field would stop the crash and leave
	 * the binding behaving as though the mod had never set a context at all.
	 *
	 * <p>So the MinecraftForge face is adapted onto the NeoForge state, in both directions, through
	 * {@code KernelForgeKeyBindings}. The same reason applies to the MinecraftForge-typed CONSTRUCTORS, which
	 * write only the dead fields and leave the NeoForge ones null — a mod using one gets a mapping whose first
	 * conflict check is a NullPointerException. Each of them gains a tail that mirrors what it wrote into the
	 * fields the game reads.
	 */
	static boolean giveKeyMappingItsMinecraftForgeFace(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.KEY_MAPPING.equals(node.name)) return false;
		// Only when the merge actually split it: both sides' state present, only one side's accessors.
		if (!MergedBaseAsm.hasField(node, "keyConflictContext", ForbricMergedBaseCompatTransformer.MF_CONTEXT) || !MergedBaseAsm.hasField(node, "keyConflictContext", ForbricMergedBaseCompatTransformer.NEO_CONTEXT)) {
			return false;
		}
		if (MergedBaseAsm.findMethod(node, "setKeyConflictContext", "(" + ForbricMergedBaseCompatTransformer.MF_CONTEXT + ")V") != null) return false;

		MergedBaseKeyRepair.addAdapted(node, "setKeyConflictContext", "(" + ForbricMergedBaseCompatTransformer.MF_CONTEXT + ")V", "(" + ForbricMergedBaseCompatTransformer.NEO_CONTEXT + ")V",
				"toNeoContext", ForbricMergedBaseCompatTransformer.MF_CONTEXT, ForbricMergedBaseCompatTransformer.NEO_CONTEXT);
		MergedBaseKeyRepair.addAdapted(node, "getKeyConflictContext", "()" + ForbricMergedBaseCompatTransformer.MF_CONTEXT, "()" + ForbricMergedBaseCompatTransformer.NEO_CONTEXT,
				"toForgeContext", ForbricMergedBaseCompatTransformer.NEO_CONTEXT, ForbricMergedBaseCompatTransformer.MF_CONTEXT);
		MergedBaseKeyRepair.addAdapted(node, "getKeyModifier", "()" + ForbricMergedBaseCompatTransformer.MF_MODIFIER, "()" + ForbricMergedBaseCompatTransformer.NEO_MODIFIER,
				"toForgeModifier", ForbricMergedBaseCompatTransformer.NEO_MODIFIER, ForbricMergedBaseCompatTransformer.MF_MODIFIER);
		MergedBaseKeyRepair.addAdapted(node, "getDefaultKeyModifier", "()" + ForbricMergedBaseCompatTransformer.MF_MODIFIER, "()" + ForbricMergedBaseCompatTransformer.NEO_MODIFIER,
				"toForgeModifier", ForbricMergedBaseCompatTransformer.NEO_MODIFIER, ForbricMergedBaseCompatTransformer.MF_MODIFIER);
		MergedBaseKeyRepair.addSetKeyModifierAndCode(node);
		int mirrored = MergedBaseKeyRepair.mirrorForgeConstructorsIntoTheLiveFields(node);

		ForbricLog.warn("[Forbric/MergedBaseCompat] gave KeyMapping its MinecraftForge accessors back and pointed "
				+ "them at the NeoForge state the game actually reads — the merge kept both ecosystems' fields but "
				+ "only one side's accessors, and the other side's fields are read by nothing (%d constructor(s) "
				+ "also mirrored)", mirrored);
		return true;
	}


	/**
	 * Adds {@code name+forgeDesc} as a one-line delegate to {@code name+neoDesc}, converting through the kernel.
	 *
	 * <p>A getter pair differs only in return type, which no Java source can express and the JVM is perfectly
	 * happy with — the descriptor is part of the identity.
	 */
	static void addAdapted(ClassNode node, String name, String forgeDesc, String neoDesc,
			String converter, String fromDesc, String toDesc) {
		boolean setter = forgeDesc.endsWith(")V");
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, forgeDesc, null, null);
		m.visitVarInsn(Opcodes.ALOAD, 0);
		if (setter) {
			m.visitVarInsn(Opcodes.ALOAD, 1);
			m.visitMethodInsn(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_KEYS, converter,
					"(Ljava/lang/Object;)Ljava/lang/Object;", false);
			m.visitTypeInsn(Opcodes.CHECKCAST, MergedBaseAsm.internal(toDesc));
			m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, name, neoDesc, false);
			m.visitInsn(Opcodes.RETURN);
			m.maxStack = 2;
			m.maxLocals = 2;
		} else {
			m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, name, neoDesc, false);
			m.visitMethodInsn(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_KEYS, converter,
					"(Ljava/lang/Object;)Ljava/lang/Object;", false);
			m.visitTypeInsn(Opcodes.CHECKCAST, MergedBaseAsm.internal(toDesc));
			m.visitInsn(Opcodes.ARETURN);
			m.maxStack = 1;
			m.maxLocals = 1;
		}
		node.methods.add(m);
	}


	/** The two-argument setter, whose second argument passes through untouched. */
	static void addSetKeyModifierAndCode(ClassNode node) {
		String forgeDesc = "(" + ForbricMergedBaseCompatTransformer.MF_MODIFIER + ForbricMergedBaseCompatTransformer.INPUT_KEY + ")V";
		String neoDesc = "(" + ForbricMergedBaseCompatTransformer.NEO_MODIFIER + ForbricMergedBaseCompatTransformer.INPUT_KEY + ")V";
		if (MergedBaseAsm.findMethod(node, "setKeyModifierAndCode", forgeDesc) != null) return;
		if (MergedBaseAsm.findMethod(node, "setKeyModifierAndCode", neoDesc) == null) return;
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "setKeyModifierAndCode", forgeDesc, null, null);
		m.visitVarInsn(Opcodes.ALOAD, 0);
		m.visitVarInsn(Opcodes.ALOAD, 1);
		m.visitMethodInsn(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_KEYS, "toNeoModifier",
				"(Ljava/lang/Object;)Ljava/lang/Object;", false);
		m.visitTypeInsn(Opcodes.CHECKCAST, MergedBaseAsm.internal(ForbricMergedBaseCompatTransformer.NEO_MODIFIER));
		m.visitVarInsn(Opcodes.ALOAD, 2);
		m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, "setKeyModifierAndCode", neoDesc, false);
		m.visitInsn(Opcodes.RETURN);
		m.maxStack = 3;
		m.maxLocals = 3;
		node.methods.add(m);
	}


	/**
	 * Copies what a MinecraftForge-typed constructor wrote into the fields the game reads.
	 *
	 * <p>Appended before every RETURN rather than woven into the assignments: the constructor may write its
	 * fields in any order, and only at the end is the final value known. Fields, not the new setters — a setter
	 * would re-enter the lookup registration the constructor has already done.
	 */
	static int mirrorForgeConstructorsIntoTheLiveFields(ClassNode node) {
		String forgeLookup = "L" + ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE) + ";";
		String neoLookup = "L" + ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.NEOFORGE) + ";";

		int mirrored = 0;
		for (MethodNode m : node.methods) {
			if (!"<init>".equals(m.name) || m.instructions == null) continue;
			if (!m.desc.contains(ForbricMergedBaseCompatTransformer.MF_CONTEXT) && !m.desc.contains(ForbricMergedBaseCompatTransformer.MF_MODIFIER)) continue;

			// WHERE, not just what. The constructor's own tail registers the binding:
			//
			//     88  ALL.put(name, this)
			//     99  GETSTATIC KeyMapping.MAP : Lnet/minecraftforge/.../KeyMappingLookup;
			//    102  ALOAD key ; ALOAD this
			//    105  INVOKEVIRTUAL  net/minecraftforge/.../KeyMappingLookup.put(Key, KeyMapping)V
			//    108  RETURN
			//
			// and that put reads the mapping back through getKeyModifier() — the MinecraftForge-faced accessor
			// this transformer adds, which reads the NEOFORGE field. Mirroring before the RETURN put the write
			// AFTER the read, so the Neo field was still null at offset 105, toForgeModifier answered for a null,
			// and Forge's lookup did computeIfAbsent on the result. Every Forge-typed key binding died in its
			// own <clinit> with an NPE raised inside MinecraftForge's code.
			//
			// So the mirror goes before the FIRST access of either lookup, and if there is none it falls back to
			// the RETURNs — the shorter constructors delegate and have no put of their own.
			AbstractInsnNode anchor = MergedBaseKeyRepair.firstLookupAccess(m, forgeLookup, neoLookup);
			boolean any = false;
			if (anchor != null) {
				m.instructions.insertBefore(anchor, MergedBaseKeyRepair.mirrorFields(node));
				any = true;
			} else {
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() != Opcodes.RETURN) continue;
					m.instructions.insertBefore(insn, MergedBaseKeyRepair.mirrorFields(node));
					any = true;
				}
			}

			// And the registration itself. Both getAll overloads read NeoForge's MAP (routeKeyMappingClickToPopulatedLookup
			// points the last one that did not at it), so a binding put into MinecraftForge's lookup is in a map
			// nothing ever reads: the key would exist, bind, show in the Controls screen and never fire. The two
			// put methods are descriptor-identical — (InputConstants$Key, KeyMapping)V — so this is a field
			// descriptor and an owner, nothing more.
			if (MergedBaseKeyRepair.retargetLookupRegistration(m, forgeLookup, neoLookup)) any = true;

			if (any) {
				m.maxStack = Math.max(m.maxStack, 3);
				mirrored++;
			}
		}
		return mirrored;
	}


	/** The three field copies, as one list. Built per insertion point because an InsnList can only be added once. */
	static InsnList mirrorFields(ClassNode node) {
		InsnList mirror = new InsnList();
		mirror.add(MergedBaseAsm.field(node, "keyConflictContext", ForbricMergedBaseCompatTransformer.MF_CONTEXT, ForbricMergedBaseCompatTransformer.NEO_CONTEXT, "toNeoContext"));
		mirror.add(MergedBaseAsm.field(node, "keyModifier", ForbricMergedBaseCompatTransformer.MF_MODIFIER, ForbricMergedBaseCompatTransformer.NEO_MODIFIER, "toNeoModifier"));
		mirror.add(MergedBaseAsm.field(node, "keyModifierDefault", ForbricMergedBaseCompatTransformer.MF_MODIFIER, ForbricMergedBaseCompatTransformer.NEO_MODIFIER, "toNeoModifier"));
		return mirror;
	}


	/** The first read of either family's {@code MAP}, which is where the constructor starts registering. */
	static AbstractInsnNode firstLookupAccess(MethodNode m, String forgeLookup, String neoLookup) {
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode field
					&& "MAP".equals(field.name)
					&& (forgeLookup.equals(field.desc) || neoLookup.equals(field.desc))) {
				return insn;
			}
		}
		return null;
	}


	/** Points a constructor's own {@code MAP.put} at the lookup the game reads. True when anything moved. */
	static boolean retargetLookupRegistration(MethodNode m, String forgeLookup, String neoLookup) {
		boolean changed = false;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode field
					&& "MAP".equals(field.name) && forgeLookup.equals(field.desc)) {
				field.desc = neoLookup;
				changed = true;
			} else if (insn instanceof MethodInsnNode call && "put".equals(call.name)
					&& ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE).equals(call.owner)) {
				call.owner = ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.NEOFORGE);
				changed = true;
			}
		}
		return changed;
	}
}
