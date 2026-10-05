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
 * Feature flags, mipmaps, key-name suffixes, loot-modifier indexes, pack overlays, and a lost switch map.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBasePackRepair {
	private MergedBasePackRepair() {
	}


	/**
	 * Sends {@code FeatureFlags.<clinit>}'s call to NeoForge's {@code FeatureFlagLoader.loadModdedFlags} to the kernel.
	 *
	 * <p>NeoForge reads each mod's declared flag file through {@code IModFile.getContents()}, and the kernel's mod
	 * files carry no jar contents, so that walk found nothing and a mod asking {@code FeatureFlags.REGISTRY} for
	 * its own flag died in its static initialiser. Same descriptor, same moment, owner swapped; the kernel helper
	 * reads the same file from the jar. Idempotent: an already-swapped call is left alone.
	 */
	static boolean letModdedFeatureFlagsRegister(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.FEATURE_FLAGS.equals(node.name)) return false;
		MethodNode clinit = MergedBaseAsm.findMethod(node, "<clinit>", "()V");
		if (clinit == null) return false;
		int swapped = 0;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& ForbricMergedBaseCompatTransformer.NEO_FEATURE_FLAG_LOADER.equals(call.owner) && ForbricMergedBaseCompatTransformer.LOAD_MODDED_FLAGS.equals(call.name)
					&& ForbricMergedBaseCompatTransformer.LOAD_MODDED_FLAGS_DESC.equals(call.desc)) {
				call.owner = ForbricMergedBaseCompatTransformer.KERNEL_FEATURE_FLAGS;
				swapped++;
			}
		}
		if (swapped == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] FeatureFlags now asks the kernel for NeoForge mods' declared feature flags — "
				+ "NeoForge's own loader walks jar contents the kernel's mod files do not carry, so those flags were never "
				+ "registered and a mod asking for its own died in its static initialiser");
		return true;
	}


	/**
	 * Lowering an atlas's mip level to fit its smallest sprite is VANILLA behaviour, and the merge made it opt-in.
	 *
	 * <p>MinecraftForge patches {@code SpriteLoader.stitch} to gate that lowering on
	 * {@code ForgeConfig.CLIENT.allowMipmapLowering()}, whose default is FALSE — its own comment says so: "When
	 * enabled, Forge will allow mipmaps to be lowered in real-time. This is the default behavior in vanilla."
	 * NeoForge's patched {@code SpriteLoader} has no such gate. The byte merge kept MinecraftForge's, so one
	 * ecosystem's opt-out became the rule for all three, including Fabric and NeoForge mods that were written
	 * against vanilla and never agreed to it.
	 *
	 * <p>What that costs is the worst shape there is. The Logistics mod (NeoForge) registers its own atlas holding
	 * an 8x8 sprite; vanilla lowers the atlas from mip 4 to 3, MinecraftForge's gate refuses, and the GPU rejects
	 * the upload — "mipLevels must be at most 4 for a texture of width 8 and height 8". That throws out of the
	 * FIRST resource reload, so Minecraft logs "Caught error loading resourcepacks, removing all selected
	 * resourcepacks" and reloads; the same atlas fails the same way; the reload never completes, and the client
	 * renders a BLACK SCREEN for the rest of the run. No crash report, no further log line, nothing on screen.
	 *
	 * <p>The gate is replaced by {@code true} — two instructions for one, no branch, so the frames this transformer
	 * does not compute are unchanged. MinecraftForge's knob still agrees with the kernel when a player sets it to
	 * true; {@code -Dforbric.mipmapLowering=off} gives its false default back.
	 */
	static boolean letTheAtlasLowerItsMipLevelLikeVanilla(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.SPRITE_LOADER.equals(node.name) || !ForbricMergedBaseCompatTransformer.mipmapLoweringEnabled()) return false;
		int forced = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
				AbstractInsnNode next = insn.getNext();
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
						&& ForbricMergedBaseCompatTransformer.FORGE_CLIENT_CONFIG.equals(call.owner) && ForbricMergedBaseCompatTransformer.MIPMAP_LOWERING.equals(call.name)
						&& "()Z".equals(call.desc)) {
					AbstractInsnNode receiver = insn.getPrevious();
					if (receiver instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
						method.instructions.remove(field);
					}
					method.instructions.set(insn, new InsnNode(Opcodes.ICONST_1));
					forced++;
				}
				insn = next;
			}
		}
		if (forced == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] SpriteLoader lowers an atlas's mip level to fit its smallest "
				+ "sprite again (%d gate(s) forced) — the merge kept MinecraftForge's opt-in, whose default is off, "
				+ "and one NeoForge mod's 8x8 sprite then killed the first resource reload and left the client black",
				forced);
		return true;
	}


	/**
	 * {@code InputConstants.getKey} must not be handed MinecraftForge's {@code ":MODIFIER"} suffix.
	 *
	 * <p>MinecraftForge extends a key binding with a modifier and WRITES it into options.txt as
	 * {@code key_key.jei.toggleOverlay:key.keyboard.o:CONTROL_OR_COMMAND}. Its own
	 * {@code Options.processOptionsKeysOnly} then reads that value and calls {@code InputConstants.getKey(value)}
	 * with the whole string BEFORE splitting the modifier off — and vanilla's {@code getKey} does
	 * {@code Integer.parseInt("o:CONTROL_OR_COMMAND")}. That is MinecraftForge's own code, unchanged by the merge:
	 * the same instruction order is in the forge-patched base, so this is not a merge artifact and switching it off
	 * does not restore anything.
	 *
	 * <p>What it costs is out of all proportion to one key: {@code Options.load} wraps the whole file in one
	 * try/catch, so a single modded binding with a modifier makes the client log "Failed to load options" and the
	 * player loses EVERY setting — video, controls, language, and the accessibility-onboarding flag, which then
	 * sits in front of the game on the next launch. JEI binds three of them by default.
	 *
	 * <p>The repair is the smallest thing that can be said: {@code name = name.split(":")[0]} at method entry. No
	 * key name in {@code Key.NAME_MAP} contains a colon, so a name without one is unchanged, and MinecraftForge's
	 * own modifier parse two instructions later still reads the suffix off the original value. Branch-free on
	 * purpose — this transformer writes with {@code ClassWriter(0)} and computes no frames.
	 */
	static boolean dropTheKeyModifierSuffixBeforeParsingAKeyName(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.INPUT_CONSTANTS.equals(node.name) || !ForbricMergedBaseCompatTransformer.keyModifierSuffixEnabled()) return false;
		MethodNode getKey = MergedBaseAsm.findMethod(node, "getKey", "(Ljava/lang/String;)Lcom/mojang/blaze3d/platform/InputConstants$Key;");
		if (getKey == null || getKey.instructions.size() == 0) return false;
		// Idempotent: the first instruction of a repaired method is the ALOAD 0 of this prologue followed by the
		// split. Re-running the pass over an already-written class must not stack a second copy.
		for (AbstractInsnNode insn = getKey.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && "java/lang/String".equals(call.owner)
					&& "split".equals(call.name)) {
				return false;
			}
		}
		InsnList prologue = new InsnList();
		prologue.add(new VarInsnNode(Opcodes.ALOAD, 0));
		prologue.add(new LdcInsnNode(":"));
		prologue.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split",
				"(Ljava/lang/String;)[Ljava/lang/String;", false));
		prologue.add(new InsnNode(Opcodes.ICONST_0));
		prologue.add(new InsnNode(Opcodes.AALOAD));
		prologue.add(new VarInsnNode(Opcodes.ASTORE, 0));
		getKey.instructions.insert(prologue);
		getKey.maxStack = Math.max(getKey.maxStack, 2);
		ForbricLog.info("[Forbric/MergedBaseCompat] InputConstants.getKey now drops MinecraftForge's \":MODIFIER\" "
				+ "suffix before parsing a key name — one modded binding with a modifier used to throw out of "
				+ "options.txt parsing, and Options.load wraps the WHOLE file, so the player lost every setting");
		return true;
	}


	/**
	 * MinecraftForge's loot-modifier manager reads {@code loot_modifiers/global_loot_modifiers.json} BY NAME as
	 * its list of enabled modifiers, then scans the directory and drops what the list does not name; NeoForge's
	 * has no list-file concept, scans the same directory with {@code IGlobalLootModifier.DIRECT_CODEC}, and logs
	 * {@code Couldn't parse data file '…global_loot_modifiers'} for every index it meets — two permanent ERROR
	 * lines on every tri-ecosystem boot (the MinecraftForge carrier ships one, mods ship another), which is what
	 * makes a genuinely broken loot modifier indistinguishable from the furniture.
	 *
	 * <p>Both managers' DIRECTORY scans now run over a view of the resource manager that hides
	 * {@code *&#47;loot_modifiers/global_loot_modifiers.json}; MinecraftForge's own by-name read of its index is on
	 * the original manager and untouched, so the one path that owns the file keeps it. NeoForge's manager has no
	 * {@code prepare} of its own, so one is synthesized ({@code super.prepare(withoutTheLegacyIndex(rm), p)});
	 * MinecraftForge's existing {@code prepare} gets the same wrap on the {@code aload_1} feeding its
	 * {@code super.prepare} call. Keyed on the {@code LOOT_MODIFIER_MANAGER} pair; each half stands down on its own.
	 */
	static boolean hideTheLegacyLootModifierIndexFromTheDirectoryScan(ClassNode node) {
		if (ForbricMergedBaseCompatTransformer.LOOT_MODIFIER_MANAGER_NEO.equals(node.name)) return MergedBasePackRepair.synthesizeNeoForgePrepare(node);
		if (ForbricMergedBaseCompatTransformer.LOOT_MODIFIER_MANAGER_FORGE.equals(node.name)) return MergedBasePackRepair.wrapMinecraftForgePrepare(node);
		return false;
	}


	static boolean synthesizeNeoForgePrepare(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.SIMPLE_JSON_LISTENER.equals(node.superName)) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] %s no longer extends SimpleJsonResourceReloadListener — the legacy "
					+ "loot-modifier index is not hidden from its scan", node.name.replace('/', '.'));
			return false;
		}
		if (MergedBaseAsm.findMethod(node, ForbricMergedBaseCompatTransformer.PREPARE, ForbricMergedBaseCompatTransformer.PREPARE_DESC) != null) return false;    // its own prepare now, or a second pass
		MethodNode prepare = new MethodNode(Opcodes.ACC_PROTECTED, ForbricMergedBaseCompatTransformer.PREPARE, ForbricMergedBaseCompatTransformer.PREPARE_DESC, null, null);
		prepare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		prepare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		prepare.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_LOOT_MODIFIERS, ForbricMergedBaseCompatTransformer.WITHOUT_INDEX, ForbricMergedBaseCompatTransformer.WITHOUT_INDEX_DESC, false));
		prepare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		prepare.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ForbricMergedBaseCompatTransformer.SIMPLE_JSON_LISTENER, ForbricMergedBaseCompatTransformer.PREPARE, ForbricMergedBaseCompatTransformer.PREPARE_DESC, false));
		prepare.instructions.add(new InsnNode(Opcodes.ARETURN));
		prepare.maxStack = 3;
		prepare.maxLocals = 3;
		node.methods.add(prepare);
		ForbricLog.info("[Forbric/MergedBaseCompat] NeoForge's LootModifierManager scans loot_modifiers/ without the legacy "
				+ "global_loot_modifiers.json index (applied at 1 site) — it has no list-file concept and logged a parse "
				+ "error for each one");
		return true;
	}


	static boolean wrapMinecraftForgePrepare(ClassNode node) {
		MethodNode prepare = MergedBaseAsm.findMethod(node, ForbricMergedBaseCompatTransformer.PREPARE, ForbricMergedBaseCompatTransformer.PREPARE_DESC);
		if (prepare == null) return false;
		MethodInsnNode site = null;
		int sites = 0;
		for (AbstractInsnNode insn = prepare.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && ForbricMergedBaseCompatTransformer.SIMPLE_JSON_LISTENER.equals(call.owner)
					&& ForbricMergedBaseCompatTransformer.PREPARE.equals(call.name) && ForbricMergedBaseCompatTransformer.PREPARE_DESC.equals(call.desc)) {
				sites++;
				site = call;
			}
			if (insn instanceof MethodInsnNode call && ForbricMergedBaseCompatTransformer.KERNEL_LOOT_MODIFIERS.equals(call.owner)) return false;    // second pass
		}
		if (sites != 1) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] MinecraftForge's LootModifierManager.prepare calls super.prepare %d "
					+ "time(s), not once — its directory scan is not wrapped", sites);
			return false;
		}
		// aload_0; aload_1; aload_2; invokespecial — wrap the manager argument, the aload_1 two instructions back.
		AbstractInsnNode profiler = MergedBaseAsm.previousReal(site.getPrevious());
		AbstractInsnNode manager = MergedBaseAsm.previousReal(profiler.getPrevious());
		if (!(profiler instanceof VarInsnNode p) || p.var != 2 || !(manager instanceof VarInsnNode m) || m.var != 1) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] MinecraftForge's LootModifierManager.prepare feeds super.prepare in a "
					+ "shape that is not aload_1/aload_2 — its directory scan is not wrapped");
			return false;
		}
		prepare.instructions.insert(manager, new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_LOOT_MODIFIERS, ForbricMergedBaseCompatTransformer.WITHOUT_INDEX,
				ForbricMergedBaseCompatTransformer.WITHOUT_INDEX_DESC, false));
		ForbricLog.info("[Forbric/MergedBaseCompat] MinecraftForge's LootModifierManager scans loot_modifiers/ without the legacy "
				+ "index too (applied at 1 site) — it still reads its own index by name, on the original manager");
		return true;
	}


	/**
	 * A data file gated by a condition the NeoForge evaluator cannot judge is IGNORED (the owning ecosystem's
	 * evaluator judges it afterwards — see {@code KernelNeoConditions}). A pack.mcmeta overlay entry has no
	 * afterwards: {@code Pack.readPackMetadata} unions both sections' overlays, so an ignored condition MOUNTS the
	 * directory. Measured on Terralith with {@code vanilla_stone_gen=false}: six placed-feature overrides under
	 * {@code enable.vanilla_stone_gen} went into the world anyway.
	 *
	 * <p>One stack-neutral insertion after {@code ConditionalOps.decodeListWithElementConditions} in
	 * {@code OverlayEntry.listCodecForPackType} — the funnel both the vanilla {@code overlays} and the
	 * {@code neoforge:overlays} section read through — wraps the list codec with
	 * {@code KernelNeoConditions.forOverlayEntries}, which makes the leniency answer a foreign type with a VETO for
	 * the duration of that decode. NeoForge's own decoder then drops the entry.
	 */
	static boolean vetoUnjudgeableOverlayConditions(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.OVERLAY_ENTRY.equals(node.name)) return false;
		MethodNode method = MergedBaseAsm.findMethod(node, ForbricMergedBaseCompatTransformer.LIST_CODEC_FOR_PACK_TYPE, ForbricMergedBaseCompatTransformer.LIST_CODEC_DESC);
		if (method == null) return false;
		MethodInsnNode site = null;
		int sites = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& ForbricMergedBaseCompatTransformer.CONDITIONAL_OPS_NEO.equals(call.owner) && ForbricMergedBaseCompatTransformer.DECODE_LIST_WITH_CONDITIONS.equals(call.name)
					&& ForbricMergedBaseCompatTransformer.CODEC_TO_CODEC.equals(call.desc)) {
				sites++;
				site = call;
			}
		}
		if (sites != 1) {
			if (sites > 1) {
				ForbricLog.warn("[Forbric/MergedBaseCompat] %s.%s reads its overlay list through %d conditional codecs, "
						+ "not one — not wrapped", ForbricMergedBaseCompatTransformer.OVERLAY_ENTRY, ForbricMergedBaseCompatTransformer.LIST_CODEC_FOR_PACK_TYPE, sites);
			}
			return false;
		}
		if (MergedBaseAsm.nextReal(site) instanceof MethodInsnNode already && ForbricMergedBaseCompatTransformer.KERNEL_NEO_CONDITIONS_CLASS.equals(already.owner)) {
			return false;    // already wrapped: idempotent
		}
		method.instructions.insert(site, new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_NEO_CONDITIONS_CLASS,
				"forOverlayEntries", ForbricMergedBaseCompatTransformer.CODEC_TO_CODEC, false));
		ForbricLog.info("[Forbric/MergedBaseCompat] pack.mcmeta overlay entries gated by a condition no evaluator here can "
				+ "judge are now VETOED through NeoForge's own drop path (applied at 1 site) — ignoring the condition used "
				+ "to mount content a mod's own config had turned off");
		return true;
	}


	/**
	 * Replaces {@code getstatic $SwitchMap; <load>; invokevirtual ordinal; iaload; lookupswitch/tableswitch} with a
	 * chain of {@code <load>; getstatic Enum.CONST; if_acmpeq <case label>} ending in {@code goto <default>} —
	 * the same three-way decision without the holder class. The branch targets are the switch's own labels, so
	 * the frames already there stay right; the sequence replaced was straight-line with an empty stack before it
	 * and after it, and the replacement is too. Both-or-nothing: a switch key the table does not name, or a
	 * shape other than the one javac emits, leaves the method untouched.
	 */
	static boolean inlineTheSwitchMapTheMergeLost(ClassNode node) {
		int inlined = 0;
		for (ForbricMergedBaseCompatTransformer.LostSwitchMap lost : ForbricMergedBaseCompatTransformer.LOST_SWITCH_MAPS) {
			if (!lost.user().equals(node.name)) continue;
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (!(insn instanceof FieldInsnNode get) || get.getOpcode() != Opcodes.GETSTATIC
							|| !lost.holder().equals(get.owner) || !lost.field().equals(get.name)) {
						continue;
					}
					AbstractInsnNode load = MergedBaseAsm.nextReal(get);
					AbstractInsnNode ordinal = MergedBaseAsm.nextReal(load);
					AbstractInsnNode iaload = MergedBaseAsm.nextReal(ordinal);
					AbstractInsnNode sw = MergedBaseAsm.nextReal(iaload);
					if (!(load instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ALOAD
							|| !(ordinal instanceof MethodInsnNode call) || !"ordinal".equals(call.name)
							|| !lost.enumType().equals(call.owner) || iaload == null || iaload.getOpcode() != Opcodes.IALOAD
							|| !(sw instanceof org.objectweb.asm.tree.LookupSwitchInsnNode
									|| sw instanceof org.objectweb.asm.tree.TableSwitchInsnNode)) {
						ForbricLog.warn("[Forbric/MergedBaseCompat] %s.%s reads %s.%s in a shape that is not javac's switch "
								+ "map — not inlined", node.name.replace('/', '.'), method.name, lost.holder(), lost.field());
						continue;
					}
					List<Integer> keys = new ArrayList<>();
					List<LabelNode> labels = new ArrayList<>();
					LabelNode dflt;
					if (sw instanceof org.objectweb.asm.tree.LookupSwitchInsnNode lookup) {
						keys.addAll(lookup.keys);
						labels.addAll(lookup.labels);
						dflt = lookup.dflt;
					} else {
						org.objectweb.asm.tree.TableSwitchInsnNode table = (org.objectweb.asm.tree.TableSwitchInsnNode) sw;
						for (int k = table.min; k <= table.max; k++) keys.add(k);
						labels.addAll(table.labels);
						dflt = table.dflt;
					}
					boolean allNamed = true;
					for (int key : keys) if (!lost.cases().containsKey(key)) allNamed = false;
					if (!allNamed) {
						ForbricLog.warn("[Forbric/MergedBaseCompat] %s.%s switches on a case the table does not name (%s) "
								+ "— not inlined", node.name.replace('/', '.'), method.name, keys);
						continue;
					}
					InsnList chain = new InsnList();
					String enumDesc = "L" + lost.enumType() + ";";
					for (int i = 0; i < keys.size(); i++) {
						chain.add(new VarInsnNode(Opcodes.ALOAD, var.var));
						chain.add(new FieldInsnNode(Opcodes.GETSTATIC, lost.enumType(), lost.cases().get(keys.get(i)), enumDesc));
						chain.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, labels.get(i)));
					}
					chain.add(new JumpInsnNode(Opcodes.GOTO, dflt));
					AbstractInsnNode last = chain.getLast();    // insertBefore empties `chain`
					method.instructions.insertBefore(get, chain);
					// Drop the five instructions, leaving any label/line/frame nodes between them where they are.
					for (AbstractInsnNode victim : List.of(get, load, ordinal, iaload, sw)) method.instructions.remove(victim);
					method.maxStack = Math.max(method.maxStack, 2);
					inlined++;
					insn = last;
				}
			}
		}
		if (inlined == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] %s decides %d enum switch(es) by direct comparison — javac's "
				+ "$SwitchMap$ holder class for them was MinecraftForge's, and the merge kept NeoForge's class of the "
				+ "same name instead, so the read was a NoSuchFieldError on every Forge ITEM_HANDLER ask of a furnace",
				node.name.replace('/', '.'), inlined);
		return true;
	}
}
