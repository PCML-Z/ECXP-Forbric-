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
 * Resource conditions, so a foreign condition type does not fail the whole registry load.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseConditionRepair {
	private MergedBaseConditionRepair() {
	}


	/**
	 * Stops one ecosystem's condition dialect from failing the other ecosystem's data files — and with them the
	 * whole registry load.
	 *
	 * <p>The merged {@code RegistryLoadTask$PendingRegistration.loadFromResource} carries NeoForge's patch: stock
	 * Minecraft's body calls {@code Decoder.parse} straight, and the merged one wraps every element in
	 * {@code ConditionalOps.createConditionalCodec} first. There is no switch on it and no per-pack scoping, so
	 * EVERY datapack-registry element from EVERY pack is judged by NeoForge's evaluator.
	 *
	 * <p>A multi-loader mod ships one data tree carrying BOTH dialects — {@code "fabric:load_conditions"} and
	 * {@code "neoforge:conditions"} in the same file — which is what Architectury emits. Its Fabric build
	 * registers the condition type on the Fabric side only, so the NeoForge dispatch cannot resolve the id and
	 * {@code RegistryDataLoader} escalates that into "Failed to load registries due to errors". The server does
	 * not start and the world does not open: a fatal, produced by ordinary mod output.
	 *
	 * <p>{@code ICondition.CODEC} is a registry dispatch built in one static initializer and reused everywhere,
	 * including by {@code LIST_CODEC} two instructions later, so ONE insertion covers datapack registries,
	 * recipes, loot tables and advancements alike. The kernel's wrapper decodes an unknown type as a condition
	 * that does not veto, leaving the judgement to the ecosystem that owns the id.
	 *
	 * <p>Inserted rather than replaced, and stack-neutral: a {@code Codec} goes in and a {@code Codec} comes out,
	 * so the existing {@code PUTSTATIC} is untouched and there is no frame to recompute.
	 */
	static boolean letForeignResourceConditionsThrough(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.ICONDITION.equals(node.name)) return false;
		MethodNode clinit = MergedBaseAsm.findMethod(node, "<clinit>", "()V");
		if (clinit == null) return false;

		FieldInsnNode target = null;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
					&& ForbricMergedBaseCompatTransformer.ICONDITION.equals(field.owner) && "CODEC".equals(field.name)
					&& ForbricMergedBaseCompatTransformer.CODEC_DESC.equals(field.desc)) {
				if (target != null) {
					ForbricLog.warn("[Forbric/MergedBaseCompat] ICondition.CODEC is assigned more than once — not "
							+ "wrapping it, because only one of the assignments would be the one that survives");
					return false;
				}
				target = field;
			}
		}
		if (target == null) return false;
		if (target.getPrevious() instanceof MethodInsnNode already
				&& ForbricMergedBaseCompatTransformer.KERNEL_NEO_CONDITIONS.equals(already.owner)) {
			return false;                       // already wrapped: idempotent
		}

		clinit.instructions.insertBefore(target, new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_NEO_CONDITIONS,
				"lenient", "(" + ForbricMergedBaseCompatTransformer.CODEC_DESC + ")" + ForbricMergedBaseCompatTransformer.CODEC_DESC, false));
		ForbricLog.info("[Forbric/MergedBaseCompat] NeoForge's resource-condition codec now tolerates a condition "
				+ "type it does not own — the merged base runs that evaluator over EVERY datapack element from "
				+ "every pack, so a Fabric mod's own condition used to fail the whole registry load and the world "
				+ "with it");
		return true;
	}


	/**
	 * The same wrap on MinecraftForge's {@code ICondition.CODEC} — the THIRD strict evaluator, and the one that
	 * had not been hit yet.
	 *
	 * <p>The merged {@code ResourceManagerRegistryLoadTask.load} calls
	 * {@code ConditionCodec.wrap} at offset 15 while its own {@code lambda$load$1} builds NeoForge's
	 * {@code ConditionalOps}: both ecosystems' evaluators are live in the same method, over every datapack
	 * registry element. {@code LootPool} names the MinecraftForge one too. So a mod whose condition type only
	 * MinecraftForge cannot resolve fails a world load exactly the way waystones did on the NeoForge side.
	 *
	 * <p>Not {@code SAFE_CODEC}, which MinecraftForge already ships and which looks like the answer:
	 * {@code <clinit>} offsets 24-35 show it is {@code CODEC.orElse(FalseCondition.INSTANCE)}, so an unparseable
	 * condition evaluates FALSE and the element is dropped. Silently missing content is worse than the crash.
	 */
	/**
	 * Converts a guest mixin's "skip this file" sentinel before the merged reader casts it and dies.
	 *
	 * <p>fabric-api's {@code SimpleJsonResourceReloadListenerMixin} is a producer and a consumer that only work
	 * as a pair, and on the merged base exactly one of them applies. The producer — a {@code @WrapOperation} on
	 * {@code Codec.parse} — returns {@code DataResult.success(SKIP_DATA_MARKER)}, a bare {@code new Object()},
	 * when a file's conditions say no. The consumer, an {@code @Inject} that recognises the marker, targets
	 * {@code lambda$scanDirectory$0(Codec,Identifier,Map,Object)}; the merge left the class carrying TWO methods
	 * of that name and the live {@code invokedynamic} binds the OTHER one,
	 * {@code (Identifier,Identifier,Map,Optional)}. So the marker reaches {@code DataResult.ifSuccess}, whose
	 * consumer casts it to {@code Optional}, and the datapack load dies: "can't proceed with server load".
	 *
	 * <p>Both {@code scanDirectory} and {@code scanDirectoryWithModifier} are repaired, not just the one observed
	 * failing. They are the same shape with the same consumer contract, the second is the one recipes use, and
	 * this file already carries the lesson about patching a call site instead of the funnel and silently missing
	 * every recipe.
	 *
	 * <p>The {@code ifSuccess} CALL is replaced rather than its receiver wrapped, and that is not a style
	 * choice. The {@code invokedynamic} that builds the consumer pops three captured values first, so the
	 * {@code DataResult} is buried under them and is never on top of the stack at any instruction boundary
	 * before the call — an insertion there operates on the captured Map instead, which is an
	 * {@code IncompatibleClassChangeError} at the first datapack. An {@code invokestatic} of the same
	 * {@code (DataResult, Consumer) -> DataResult} shape moves nothing.
	 *
	 * <p>Idempotent by construction: the second pass finds no {@code ifSuccess} left to replace.
	 */
	static boolean translateAGuestsPrivateSkipMarker(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.JSON_RELOAD_LISTENER.equals(node.name)) return false;

		int repaired = 0;
		for (MethodNode method : node.methods) {
			if (!"scanDirectory".equals(method.name) && !"scanDirectoryWithModifier".equals(method.name)) continue;
			if (method.instructions == null) continue;

			AbstractInsnNode call = null;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode m && "ifSuccess".equals(m.name)
						&& "com/mojang/serialization/DataResult".equals(m.owner)) {
					if (call != null) {
						ForbricLog.warn("[Forbric/MergedBaseCompat] %s.%s has more than one DataResult.ifSuccess — "
								+ "not repairing it, because which one receives the guest's skip marker is no "
								+ "longer decidable from the shape", node.name, method.name);
						call = null;
						break;
					}
					call = insn;
				}
			}
			if (call == null) continue;

			method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_FABRIC_CONDITIONS,
					"ifSuccessWithoutAForeignSkipMarker",
					"(" + ForbricMergedBaseCompatTransformer.DATA_RESULT + "Ljava/util/function/Consumer;)" + ForbricMergedBaseCompatTransformer.DATA_RESULT, false));
			repaired++;
		}
		if (repaired == 0) return false;

		ForbricLog.info("[Forbric/MergedBaseCompat] a guest mixin's private skip marker is now translated before "
				+ "%s casts it (%d reader(s) repaired) — fabric-api's condition mixin applies only half here, and "
				+ "the half that runs produces a bare Object where the half that does not would have removed the "
				+ "file. Unrepaired, one condition-gated data file whose condition is false stops the server "
				+ "starting at all", node.name, repaired);
		return true;
	}


	static boolean letForeignResourceConditionsThroughMinecraftForge(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.FORGE_ICONDITION.equals(node.name)) return false;
		MethodNode clinit = MergedBaseAsm.findMethod(node, "<clinit>", "()V");
		if (clinit == null) return false;

		FieldInsnNode target = null;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
					&& ForbricMergedBaseCompatTransformer.FORGE_ICONDITION.equals(field.owner) && "CODEC".equals(field.name)
					&& ForbricMergedBaseCompatTransformer.CODEC_DESC.equals(field.desc)) {
				if (target != null) {
					ForbricLog.warn("[Forbric/MergedBaseCompat] MinecraftForge's ICondition.CODEC is assigned more "
							+ "than once — not wrapping it, because only one of the assignments would survive");
					return false;
				}
				target = field;
			}
		}
		if (target == null) return false;
		if (target.getPrevious() instanceof MethodInsnNode already
				&& ForbricMergedBaseCompatTransformer.KERNEL_FORGE_CONDITIONS.equals(already.owner)) {
			return false;                       // already wrapped: idempotent
		}

		clinit.instructions.insertBefore(target, new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_FORGE_CONDITIONS,
				"lenient", "(" + ForbricMergedBaseCompatTransformer.CODEC_DESC + ")" + ForbricMergedBaseCompatTransformer.CODEC_DESC, false));
		ForbricLog.info("[Forbric/MergedBaseCompat] MinecraftForge's resource-condition codec now tolerates a "
				+ "condition type it does not own — the merged base runs that evaluator over every datapack "
				+ "registry element AND every loot pool, so another ecosystem's condition used to fail the whole "
				+ "registry load and the world with it. OPTIONAL_FEILD_CODEC and SAFE_CODEC derive from CODEC "
				+ "later in the same <clinit>, so all three readers inherit this");
		return true;
	}


	/**
	 * Gives {@code fabric:load_conditions} an evaluator again, at the one place every consumer funnels through.
	 *
	 * <p>The other half of {@link #letForeignResourceConditionsThrough}. That one stopped NeoForge's evaluator
	 * failing a whole world load over an id it does not own; this one makes the answer come from the mod that
	 * does own it. fabric-api reads that key from exactly two mixins and the merged base defeats both — one
	 * anchors at a {@code Decoder.parse} NeoForge's patch replaced with {@code Codec.parse}, the other targets a
	 * lambda whose descriptor the same patch changed — and the kernel's own {@code defaultRequire} rewrite turns
	 * the first into a SILENT soft-skip. So every Fabric mod's conditional data file has loaded unconditionally
	 * here, and a config toggle meant to gate content did nothing.
	 *
	 * <p>{@code ConditionalOps} has four public factories and all four funnel into
	 * {@code createConditionalCodecWithConditions(Codec, String)}, so wrapping that one covers the datapack
	 * registries, recipes, loot tables and advancements together. A per-call-site patch would have missed
	 * recipes, which reach it through {@code scanDirectoryWithModifier} rather than {@code scanDirectory}.
	 *
	 * <p>Inserted immediately before the method's single {@code ARETURN}, where the finished {@code Codec} is
	 * already the only thing on the stack: a {@code Codec} goes in and a {@code Codec} comes out, so nothing
	 * moves and there is no frame to recompute. More than one {@code ARETURN} means the method is not the shape
	 * this reasoning was checked against, and the pass stands down whole rather than wrapping one exit.
	 */
	static boolean letFabricResourceConditionsDecide(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.CONDITIONAL_OPS.equals(node.name)) return false;
		MethodNode factory = MergedBaseAsm.findMethod(node, "createConditionalCodecWithConditions", ForbricMergedBaseCompatTransformer.CONDITIONAL_FACTORY);
		if (factory == null) return false;

		AbstractInsnNode exit = null;
		for (AbstractInsnNode insn = factory.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.ARETURN) continue;
			if (exit != null) {
				ForbricLog.warn("[Forbric/MergedBaseCompat] ConditionalOps' codec factory has more than one exit — "
						+ "not wrapping it, because wrapping one of them would judge some data files and not "
						+ "others with no way to tell which");
				return false;
			}
			exit = insn;
		}
		if (exit == null) return false;
		if (exit.getPrevious() instanceof MethodInsnNode already
				&& ForbricMergedBaseCompatTransformer.KERNEL_FABRIC_CONDITIONS.equals(already.owner)) {
			return false;                       // already wrapped: idempotent
		}

		factory.instructions.insertBefore(exit, new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_FABRIC_CONDITIONS,
				"alsoAskFabric", "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", false));

		int funnelled = 0;
		for (MethodNode method : node.methods) {
			if (method == factory) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && ForbricMergedBaseCompatTransformer.CONDITIONAL_OPS.equals(call.owner)
						&& call.name.startsWith("createConditionalCodec")) {
					funnelled++;
					break;
				}
			}
		}
		ForbricLog.info("[Forbric/MergedBaseCompat] fabric:load_conditions has an evaluator again: ConditionalOps' "
				+ "one codec factory is wrapped and %d other public entry point(s) funnel through it — datapack "
				+ "registries, recipes, loot tables and advancements all decode through it. fabric-api's own two "
				+ "mixins for this cannot apply on the merged base", funnelled);
		return true;
	}


	/**
	 * Puts NeoForge's biome/structure modifier pass behind a guard instead of behind a neuter.
	 *
	 * <p>{@code ServerLifecycleHooks.runModifiers} was neutered because {@code neoforge:biome_modifier} was not a
	 * declared datapack registry, and its first instruction is a {@code lookupOrThrow} for exactly that. It IS
	 * declared now — the kernel posts NeoForge's {@code DataPackRegistryEvent.NewRegistry} and both modifier
	 * registries come back among the declared ones — so the neuter costs every NeoForge mod that adds ores, mobs
	 * or features to a biome through {@code data/<ns>/neoforge/biome_modifier/*.json}.
	 *
	 * <p>Simply dropping the neuter is not the same thing, and the difference matters: the merged
	 * {@code DedicatedServer} and {@code IntegratedServer} both call NeoForge's {@code handleServerAboutToStart},
	 * which calls {@code runModifiers} FIRST and posts {@code ServerAboutToStartEvent} after it. An unguarded
	 * {@code lookupOrThrow} there does not cost the modifiers, it costs the boot — and it would do so on a
	 * user's machine, over a registry whose presence depends on what the kernel managed to declare that run.
	 *
	 * <p>So the CALL SITE moves to the kernel, which runs the same method reflectively inside a
	 * try/catch and reports the modifier COUNTS either way. Counting is the point: "ran without throwing" and
	 * "applied something" are different claims, and only the second one tells a declared-but-empty registry
	 * apart from a working pipeline.
	 */
	static boolean guardNeoForgesWorldModifierPass(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.NEO_SERVER_LIFECYCLE_HOOKS.equals(node.name)) return false;
		int guarded = 0;
		for (MethodNode method : node.methods) {
			if (!"handleServerAboutToStart".equals(method.name)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !ForbricMergedBaseCompatTransformer.NEO_SERVER_LIFECYCLE_HOOKS.equals(call.owner)
						|| !"runModifiers".equals(call.name) || !ForbricMergedBaseCompatTransformer.RUN_MODIFIERS.equals(call.desc)) {
					continue;
				}
				call.owner = ForbricMergedBaseCompatTransformer.KERNEL_NEO_WORLDGEN;
				call.name = "beforeServerStart";
				guarded++;
			}
		}
		if (guarded == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] NeoForge's biome/structure modifier pass now runs through the "
				+ "kernel's guard (%d call site(s)) — it used to be neutered outright, so every mod that changes a "
				+ "biome through a neoforge:biome_modifier did nothing at all", guarded);
		return true;
	}
}
