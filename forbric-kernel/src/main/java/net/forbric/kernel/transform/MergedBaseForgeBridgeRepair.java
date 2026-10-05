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
 * MinecraftForge faces the merge left unwired: fluids, particles, ingredients, reload listeners, NBT builders.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseForgeBridgeRepair {
	private MergedBaseForgeBridgeRepair() {
	}


	/**
	 * Gives {@code CompoundTag} back the {@code builder()} static every {@code IForgeBlockPos.toCompoundTag()} and
	 * {@code ForgeHooks.createEmptyStructure} links against.
	 *
	 * <p>Genuine Forge patches {@code public static INBTBuilder$Builder builder()} into {@code CompoundTag} with a
	 * body that {@code new}s {@code CompoundTag$1} — an anonymous class the byte merge could not carry, because the
	 * merged {@code CompoundTag$1} is a DIFFERENT anonymous class (the "pipeline-divergent anonymous sibling" in
	 * merge-conflicts.txt). So the method was dropped whole, and a Forge mod is one ordinary call away from
	 * {@code NoSuchMethodError} with a stack that names the mod, not the merge.
	 *
	 * <p>The body emitted here is not Forge's: it is {@code INBTBuilder.nbt()}'s own four instructions
	 * ({@code NEW INBTBuilder$Builder; DUP; INVOKESPECIAL <init>; ARETURN}), which is what Forge's
	 * {@code CompoundTag$1.nbt()} reduces to — the anonymous class only existed to implement the interface. Nothing
	 * is invented: the carrier type is real, its no-arg constructor is public, and the descriptor is the one the
	 * carrier's call sites carry. {@link ForeignType} does not apply: NeoForge has no {@code CompoundTag.builder}.
	 * A rebuilt base that carries the method makes this stand down.
	 */
	static boolean addTheMissingNbtBuilderFactory(ClassNode node) {
		if (!"net/minecraft/nbt/CompoundTag".equals(node.name)) return false;
		if (MergedBaseAsm.hasMethod(node, "builder", ForbricMergedBaseCompatTransformer.NBT_BUILDER_FACTORY_DESC)) return false;

		MethodNode factory = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "builder",
				ForbricMergedBaseCompatTransformer.NBT_BUILDER_FACTORY_DESC, null, null);
		factory.instructions.add(new TypeInsnNode(Opcodes.NEW, ForbricMergedBaseCompatTransformer.FORGE_NBT_BUILDER));
		factory.instructions.add(new InsnNode(Opcodes.DUP));
		factory.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ForbricMergedBaseCompatTransformer.FORGE_NBT_BUILDER, "<init>", "()V", false));
		factory.instructions.add(new InsnNode(Opcodes.ARETURN));
		factory.maxStack = 2;
		factory.maxLocals = 0;
		node.methods.add(factory);
		ForbricLog.warn("[Forbric/MergedBaseCompat] CompoundTag.builder() — 1 method added: genuine Forge's body news "
				+ "CompoundTag$1, an anonymous class the merge could not carry (the merged CompoundTag$1 is a different "
				+ "class), so the body emitted is INBTBuilder.nbt()'s own; IForgeBlockPos.toCompoundTag() and "
				+ "ForgeHooks.createEmptyStructure link again");
		return true;
	}


	/**
	 * Posts MinecraftForge's {@code AddReloadListenerEvent} from the merged server reload.
	 *
	 * <p>Merged {@code ReloadableServerResources.lambda$loadResources$2} calls only NeoForge's
	 * {@code EventHooks.onResourceReload}; the merged base names Forge's event nowhere. One owner redirect, same
	 * name and descriptor, to {@code KernelForgeReload.onResourceReload}, whose body calls NeoForge's hook and then
	 * the carrier's own {@code ForgeEventFactory.onResourceReload}. Exactly one call site is expected; more means
	 * an unrecognised base and the repair stands down whole. Idempotent: a second pass finds no NeoForge-owned call.
	 * The kill switch lives in the helper ({@code -Dforbric.forgeReloadListeners=off}), so the redirect is inert
	 * rather than absent when it is off.
	 */
	static boolean postMinecraftForgesReloadListenerEvent(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.RELOADABLE_SERVER_RESOURCES.equals(node.name)) return false;
		String neo = ForeignType.EVENT_HOOKS.internal(Ecosystem.NEOFORGE);
		List<MethodInsnNode> calls = new java.util.ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
						&& neo.equals(call.owner) && "onResourceReload".equals(call.name)
						&& ForbricMergedBaseCompatTransformer.RELOAD_HOOK_DESC.equals(call.desc)) {
					calls.add(call);
				}
			}
		}
		if (calls.isEmpty()) return false;
		if (calls.size() != 1) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] ReloadableServerResources calls EventHooks.onResourceReload "
					+ "%d times, not once — not redirecting any of them, because MinecraftForge's reload event would "
					+ "then be posted for some reloads and not others", calls.size());
			return false;
		}
		calls.getFirst().owner = ForbricMergedBaseCompatTransformer.KERNEL_FORGE_RELOAD;
		ForbricLog.info("[Forbric/MergedBaseCompat] ReloadableServerResources now posts both families' reload-listener "
				+ "events (1 call site) — the merged base posted only NeoForge's, so a traditional-Forge mod's "
				+ "AddReloadListenerEvent listeners never ran and its JSON data loaders were never registered");
		return true;
	}


	/**
	 * Gives MinecraftForge's {@code AddReloadListenerEvent.getConditionContext()} an answer instead of a
	 * {@code NoSuchMethodError}.
	 *
	 * <p>The carrier compiles it as {@code invokevirtual ReloadableServerResources.getConditionContext()} returning
	 * Forge's {@code ICondition$IContext}; the merged class declares only the NeoForge-typed overload. The one
	 * invocation is rewritten to {@code invokestatic KernelForgeConditions.contextOf(ReloadableServerResources)} —
	 * the receiver already on the stack becomes the argument, the Forge-typed context comes back, nothing else
	 * moves. This edits a CARRIER class, as {@link #nameTheReloadListenersNeoForgeRefusesToName} does. Exactly one
	 * site expected; idempotent once the kernel owner is present.
	 */
	static boolean giveMinecraftForgesReloadEventItsConditionContext(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.FORGE_RELOAD_EVENT.equals(node.name)) return false;
		List<MethodInsnNode> calls = new java.util.ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (ForbricMergedBaseCompatTransformer.KERNEL_FORGE_CONDITIONS.equals(call.owner) && "contextOf".equals(call.name)) return false;
				if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && ForbricMergedBaseCompatTransformer.RELOADABLE_SERVER_RESOURCES.equals(call.owner)
						&& "getConditionContext".equals(call.name) && ForbricMergedBaseCompatTransformer.FORGE_CONDITION_CONTEXT_DESC.equals(call.desc)) {
					calls.add(call);
				}
			}
		}
		if (calls.size() != 1) {
			if (!calls.isEmpty()) {
				ForbricLog.warn("[Forbric/MergedBaseCompat] AddReloadListenerEvent asks for its condition context at "
						+ "%d sites, not one — leaving it alone", calls.size());
			}
			return false;
		}
		MethodInsnNode call = calls.getFirst();
		call.setOpcode(Opcodes.INVOKESTATIC);
		call.owner = ForbricMergedBaseCompatTransformer.KERNEL_FORGE_CONDITIONS;
		call.name = "contextOf";
		call.desc = "(L" + ForbricMergedBaseCompatTransformer.RELOADABLE_SERVER_RESOURCES + ";)L" + ForbricMergedBaseCompatTransformer.FORGE_ICONDITION + "$IContext;";
		call.itf = false;
		ForbricLog.info("[Forbric/MergedBaseCompat] MinecraftForge's AddReloadListenerEvent now gets a condition context "
				+ "adapted from NeoForge's (1 call site) — the Forge-typed accessor it compiled against does not "
				+ "exist on the merged ReloadableServerResources, so asking for it was a NoSuchMethodError");
		return true;
	}


	/**
	 * Lets MinecraftForge ingredient types decode through the carrier's own dispatch.
	 *
	 * <p>Merged {@code Ingredient.<clinit>} stores {@code IngredientCodecs.codec(base)} into the single
	 * {@code CODEC} with no Forge dispatch in front of it, so {@code forge:intersection} & co. were a recipe
	 * parsing error. One instruction inserted immediately before that {@code PUTSTATIC}:
	 * {@code KernelForgeIngredients.alsoAskMinecraftForge(Codec)Codec}, which returns
	 * {@code ForgeHooks.ingredientBaseCodec(neo)} — Forge's real {@code either(registry dispatch, base)} with the
	 * NeoForge codec as its base. Raw {@code Codec} in and out, stack unchanged; the shape of
	 * {@link #letFabricResourceConditionsDecide}. Recognised only when the previous real instruction is NeoForge's
	 * factory; already-wrapped stands down (idempotent), anything else stands down and says so. The kill switch
	 * lives in the helper ({@code -Dforbric.forgeIngredients=off}).
	 */
	static boolean letMinecraftForgeIngredientTypesDecode(ClassNode node) {
		if (!"net/minecraft/world/item/crafting/Ingredient".equals(node.name)) return false;
		MethodNode clinit = MergedBaseAsm.findMethod(node, "<clinit>", "()V");
		if (clinit == null) return false;
		FieldInsnNode store = null;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
					&& node.name.equals(field.owner) && "CODEC".equals(field.name)
					&& "Lcom/mojang/serialization/Codec;".equals(field.desc)) {
				if (store != null) {
					ForbricLog.warn("[Forbric/MergedBaseCompat] Ingredient.<clinit> stores CODEC more than once — not "
							+ "wrapping it, because the Forge dispatch would then cover one store and not the other");
					return false;
				}
				store = field;
			}
		}
		if (store == null) return false;
		AbstractInsnNode previous = store.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		if (previous instanceof MethodInsnNode already && ForbricMergedBaseCompatTransformer.KERNEL_FORGE_INGREDIENTS.equals(already.owner)) {
			return false;                       // already wrapped: idempotent
		}
		if (!(previous instanceof MethodInsnNode factory) || factory.getOpcode() != Opcodes.INVOKESTATIC
				|| !ForbricMergedBaseCompatTransformer.NEO_INGREDIENT_CODECS.equals(factory.owner) || !"codec".equals(factory.name)
				|| !ForbricMergedBaseCompatTransformer.CODEC_TO_CODEC.equals(factory.desc)) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] Ingredient.CODEC is not stored straight from NeoForge's "
					+ "IngredientCodecs.codec — leaving it alone rather than wrapping an unrecognised shape");
			return false;
		}
		clinit.instructions.insertBefore(store, new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_FORGE_INGREDIENTS,
				"alsoAskMinecraftForge", ForbricMergedBaseCompatTransformer.CODEC_TO_CODEC, false));
		ForbricLog.info("[Forbric/MergedBaseCompat] Ingredient.CODEC now asks MinecraftForge's ingredient serializers "
				+ "before NeoForge's — forge:intersection/difference/compound/nbt and mod-registered Forge ingredient "
				+ "types were a recipe parsing error on the merged base");
		return true;
	}


	/**
	 * Lets a MinecraftForge fluid supply its own render model and tint from {@code FluidRenderer.tesselate}.
	 *
	 * <p>Vanilla 26.2's {@code FluidStateModelSet} knows water and lava and answers the missing model for anything
	 * else; genuine Forge's only seam is inside {@code tesselate} — after the model lookup it asks
	 * {@code IClientFluidTypeExtensions.of(fluidState).getModel(...)}, and where the model carries no tint source it
	 * asks {@code getTintColor()} instead of {@code -1}. The merge kept NeoForge's tesselate, with neither ask, so
	 * every Forge modded fluid drew as the missing texture. Two sites, one repair, one flag:
	 * <ul>
	 * <li>A: after the single {@code FluidStateModelSet.get(FluidState)} and its {@code ASTORE n}, insert
	 * {@code ALOAD n; ALOAD 5; ALOAD 1; ALOAD 2; INVOKESTATIC KernelForgeFluids.model; ASTORE n} — stack empty in,
	 * empty out, no label crossed (locals: this=0, level=1, pos=2, output=3, blockState=4, fluidState=5).</li>
	 * <li>B: the {@code IFNULL} after {@code FluidModel.fluidTintSource()} targets {@code ICONST_M1; ISTORE k}; the
	 * constant becomes {@code ALOAD 5; INVOKESTATIC KernelForgeFluids.tintColor} — an int is pushed on both arms,
	 * the label keeps its empty-stack frame.</li>
	 * </ul>
	 * Whole-or-nothing: unless both shapes match exactly once, nothing is edited and the reason is logged.
	 * Idempotent once the kernel owner is named. The flag ({@code -Dforbric.forgeFluidModels=off}) lives in the
	 * helper, which then returns the model by identity and {@code -1}.
	 */
	static boolean letMinecraftForgeFluidsChooseTheirModel(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.FLUID_RENDERER.equals(node.name)) return false;
		MethodNode tesselate = MergedBaseAsm.findMethod(node, "tesselate", ForbricMergedBaseCompatTransformer.TESSELATE_DESC);
		if (tesselate == null) return false;

		VarInsnNode modelStore = null;
		InsnNode minusOne = null;
		int lookups = 0, tintArms = 0;
		for (AbstractInsnNode insn = tesselate.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && ForbricMergedBaseCompatTransformer.KERNEL_FORGE_FLUIDS.equals(call.owner)) return false;
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
					&& "net/minecraft/client/renderer/block/FluidStateModelSet".equals(call.owner)
					&& "get".equals(call.name) && ("(" + ForbricMergedBaseCompatTransformer.FLUID_STATE + ")" + ForbricMergedBaseCompatTransformer.FLUID_MODEL).equals(call.desc)) {
				lookups++;
				if (MergedBaseAsm.nextReal(call) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) modelStore = store;
			}
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
					&& "net/minecraft/client/renderer/block/FluidModel".equals(call.owner)
					&& "fluidTintSource".equals(call.name)
					&& MergedBaseAsm.nextReal(call) instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNULL) {
				AbstractInsnNode target = jump.label;
				while (target != null && target.getOpcode() < 0) target = target.getNext();
				if (target instanceof InsnNode constant && constant.getOpcode() == Opcodes.ICONST_M1
						&& MergedBaseAsm.nextReal(constant) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE) {
					tintArms++;
					minusOne = constant;
				}
			}
		}
		if (lookups != 1 || modelStore == null || tintArms != 1) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] FluidRenderer.tesselate does not have the expected shape "
					+ "(%d model lookup(s), store %s, %d tint fallback arm(s)) — leaving MinecraftForge fluid models "
					+ "unbridged rather than editing half of it", lookups, modelStore != null, tintArms);
			return false;
		}

		int slot = modelStore.var;
		InsnList funnel = new InsnList();
		funnel.add(new VarInsnNode(Opcodes.ALOAD, slot));
		funnel.add(new VarInsnNode(Opcodes.ALOAD, 5));
		funnel.add(new VarInsnNode(Opcodes.ALOAD, 1));
		funnel.add(new VarInsnNode(Opcodes.ALOAD, 2));
		funnel.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_FORGE_FLUIDS, "model", ForbricMergedBaseCompatTransformer.FLUID_MODEL_FUNNEL_DESC, false));
		funnel.add(new VarInsnNode(Opcodes.ASTORE, slot));
		tesselate.instructions.insert(modelStore, funnel);

		InsnList tint = new InsnList();
		tint.add(new VarInsnNode(Opcodes.ALOAD, 5));
		tint.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_FORGE_FLUIDS, "tintColor", "(" + ForbricMergedBaseCompatTransformer.FLUID_STATE + ")I", false));
		tesselate.instructions.insert(minusOne, tint);
		tesselate.instructions.remove(minusOne);
		tesselate.maxStack = Math.max(tesselate.maxStack, 4);
		ForbricLog.info("[Forbric/MergedBaseCompat] FluidRenderer.tesselate now asks a MinecraftForge fluid's client "
				+ "extensions for its model and tint — the merge kept NeoForge's tesselate, which never asks, so every "
				+ "Forge modded fluid drew as the missing texture");
		return true;
	}


	/**
	 * Writes {@code WeightedVariants.first} in {@code <init>}, from the local the merged constructor already computes.
	 *
	 * <p>Forge's {@code particleMaterial(ModelData)} reads {@code first} (its only reader in the base) and the merge
	 * dropped the write, so a Forge mod asking a weighted block model for its particle sprite the Forge way NPEs.
	 * Genuine Forge's constructor writes it from the same {@code getFirst()/value()} chain the merged constructor
	 * still computes into local 2; three instructions after that {@code ASTORE 2} restore it. Stands down if
	 * anything already writes the field (rebuilt base) or the chain has a different shape.
	 */
	static boolean giveMinecraftForgesParticleLookupItsFirstVariant(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.WEIGHTED_VARIANTS.equals(node.name)) return false;
		String desc = "L" + ForbricMergedBaseCompatTransformer.BLOCK_STATE_MODEL + ";";
		if (!MergedBaseAsm.hasField(node, "first", desc)) return false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
						&& ForbricMergedBaseCompatTransformer.WEIGHTED_VARIANTS.equals(field.owner) && "first".equals(field.name)) return false;
			}
		}
		MethodNode init = MergedBaseAsm.findMethod(node, "<init>", "(Lnet/minecraft/util/random/WeightedList;)V");
		if (init == null) return false;
		VarInsnNode store = null;
		for (AbstractInsnNode insn = init.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ASTORE || var.var != 2) continue;
			// previousReal answers the nearest real instruction AT or before its cursor, so step off each one first.
			AbstractInsnNode a = MergedBaseAsm.previousReal(var.getPrevious()), b = a == null ? null : MergedBaseAsm.previousReal(a.getPrevious()),
					c = b == null ? null : MergedBaseAsm.previousReal(b.getPrevious()), d = c == null ? null : MergedBaseAsm.previousReal(c.getPrevious());
			if (a instanceof TypeInsnNode castModel && castModel.getOpcode() == Opcodes.CHECKCAST
					&& ForbricMergedBaseCompatTransformer.BLOCK_STATE_MODEL.equals(castModel.desc)
					&& b instanceof MethodInsnNode value && "net/minecraft/util/random/Weighted".equals(value.owner)
					&& "value".equals(value.name)
					&& c instanceof TypeInsnNode castWeighted && castWeighted.getOpcode() == Opcodes.CHECKCAST
					&& "net/minecraft/util/random/Weighted".equals(castWeighted.desc)
					&& d instanceof MethodInsnNode first && "getFirst".equals(first.name)) {
				store = var;
				break;
			}
		}
		if (store == null) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] WeightedVariants.<init> no longer computes the first model into "
					+ "local 2 the way the merge left it — not writing 'first'");
			return false;
		}
		InsnList write = new InsnList();
		write.add(new VarInsnNode(Opcodes.ALOAD, 0));
		write.add(new VarInsnNode(Opcodes.ALOAD, 2));
		write.add(new FieldInsnNode(Opcodes.PUTFIELD, ForbricMergedBaseCompatTransformer.WEIGHTED_VARIANTS, "first", desc));
		init.instructions.insert(store, write);
		init.maxStack = Math.max(init.maxStack, 2);
		ForbricLog.warn("[Forbric/MergedBaseCompat] WeightedVariants.first is written again (1 field, in <init>) — "
				+ "Forge's particleMaterial(ModelData) is its only reader and the merge dropped genuine Forge's write");
		return true;
	}


	static boolean addMissingForgeFluidTypeBridge(ClassNode node) {
		if ((node.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (!node.name.startsWith("net/minecraft/world/level/material/")) return false;
		if (!node.interfaces.contains("net/neoforged/neoforge/common/extensions/IFluidExtension")) return false;
		if (MergedBaseAsm.hasMethod(node, "getFluidType", "()Lnet/minecraftforge/fluids/FluidType;")) return false;

		MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
				"getFluidType", "()Lnet/minecraftforge/fluids/FluidType;", null, null);
		bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/forbric/kernel/interop/ForgeRuntimeInterop",
				"forgeFluidType", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
		bridge.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraftforge/fluids/FluidType"));
		bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
		bridge.maxStack = 1;
		bridge.maxLocals = 1;
		node.methods.add(bridge);
		ForbricLog.warn("[Forbric/MergedBaseCompat] added Forge FluidType bridge to %s",
				node.name.replace('/', '.'));
		return true;
	}
}
