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
 * Items, tooltips, creative tabs, and GUI methods that inherited two defaults.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseGuiRepair {
	private MergedBaseGuiRepair() {
	}


	static boolean dropInterfaceDefaultShadowingOverrides(ClassNode node) {
		if (!node.name.startsWith("net/minecraft/client/gui/")) return false;
		if (node.interfaces == null || !node.interfaces.contains(ForbricMergedBaseCompatTransformer.CONTAINER_EVENT_HANDLER) || node.methods == null) {
			return false;
		}

		int before = node.methods.size();
		node.methods.removeIf(method -> MergedBaseGuiRepair.isPureInterfaceDefaultDelegate(node, method));
		int removed = before - node.methods.size();
		if (removed == 0) return false;

		ForbricLog.debug("[Forbric/MergedBaseCompat] dropped %d interface-default-shadowing override(s) from %s",
				removed, node.name.replace('/', '.'));
		return true;
	}


	/**
	 * Whether {@code method}'s entire body is {@code ContainerEventHandler.super.<same method>(args…)}, for one of
	 * the {@link #SHADOWABLE} methods. Keyed to that set on purpose — see
	 * {@link #dropInterfaceDefaultShadowingOverrides} for why matching on body shape alone is unsafe.
	 */
	static boolean isPureInterfaceDefaultDelegate(ClassNode node, MethodNode method) {
		if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (!ForbricMergedBaseCompatTransformer.SHADOWABLE.contains(method.name + method.desc)) return false;
		if (method.instructions == null) return false;

		java.util.List<AbstractInsnNode> body = new java.util.ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) body.add(insn);
		}

		Type[] args = Type.getArgumentTypes(method.desc);
		// this + one load per parameter + the interface-default call + the return, and NOTHING else.
		if (body.size() != args.length + 3) return false;

		if (!(body.get(0) instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) {
			return false;
		}

		int slot = 1;
		for (int i = 0; i < args.length; i++) {
			if (!(body.get(1 + i) instanceof VarInsnNode load)
					|| load.getOpcode() != args[i].getOpcode(Opcodes.ILOAD) || load.var != slot) {
				return false;
			}
			slot += args[i].getSize();
		}

		if (!(body.get(args.length + 1) instanceof MethodInsnNode call)) return false;
		if (call.getOpcode() != Opcodes.INVOKESPECIAL || !call.itf) return false;
		if (!call.name.equals(method.name) || !call.desc.equals(method.desc)) return false;
		if (!ForbricMergedBaseCompatTransformer.CONTAINER_EVENT_HANDLER.equals(call.owner)) return false;

		return body.get(args.length + 2).getOpcode() == Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN);
	}


	/**
	 * Sends the block-placement hook to NeoForge, whose type the merged snapshot list actually has.
	 *
	 * <p>{@code Level.capturedBlockSnapshots} survived the merge as
	 * {@code ArrayList<net.neoforged.neoforge.common.util.BlockSnapshot>} — NeoForge's element type won, and there is
	 * only ONE such field. But {@code ItemStack.useOn} kept calling MINECRAFTFORGE's
	 * {@code ForgeHooks.onPlaceItemIntoWorld}, which drains that same list expecting
	 * {@code net.minecraftforge.common.util.BlockSnapshot}. So placing ANY block threw
	 * {@code ClassCastException: neoforge…BlockSnapshot cannot be cast to minecraftforge…BlockSnapshot} on the
	 * server thread while handling {@code use_item_on} — the integrated server died the instant you right-clicked.
	 *
	 * <p>{@code CommonHooks.onPlaceItemIntoWorld(UseOnContext)} is NeoForge's counterpart with an IDENTICAL
	 * descriptor, so retargeting the {@code invokestatic} is type-exact and makes the consumer match the producer.
	 * Cost: MinecraftForge mods' {@code BlockEvent.EntityPlaceEvent} no longer fires (NeoForge's does). That is the
	 * same trade the merge already made for the snapshot type itself — the alternative is that nobody can place
	 * anything at all.
	 */
	static boolean routePlaceItemHookToNeoForge(ClassNode node) {
		if (!node.name.startsWith("net/minecraft/") || node.methods == null) return false;

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;

			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
				if (!"net/minecraftforge/common/ForgeHooks".equals(call.owner)
						|| !"onPlaceItemIntoWorld".equals(call.name)) {
					continue;
				}
				call.owner = "net/neoforged/neoforge/common/CommonHooks";
				changed = true;
			}
		}
		if (!changed) return false;

		ForbricLog.warn("[Forbric/MergedBaseCompat] routed %s's block-placement hook to NeoForge — the merged "
				+ "Level.capturedBlockSnapshots holds NeoForge BlockSnapshots, so MinecraftForge's hook threw "
				+ "ClassCastException on every block placed", node.name.replace('/', '.'));
		return true;
	}


	/**
	 * Lets a creative tab SKIP an empty stack instead of aborting the whole creative menu.
	 *
	 * <p>{@code CreativeModeTab.Output.accept(ItemLike)} turns its argument into {@code new ItemStack(itemLike)},
	 * which collapses to {@code ItemStack.EMPTY} (count 0) whenever the block has no item form. NeoForge's output
	 * wrapper treats that as a programming error and throws {@code IllegalArgumentException: The stack count must
	 * be 1}; MinecraftForge's path just drops the entry.
	 *
	 * <p>On the merged base NeoForge won {@code CreativeModeTab.buildContents}, so a MINECRAFTFORGE mod's tab is
	 * validated by NEOFORGE's stricter contract — a cross-ecosystem split like the Forge/NeoForge {@code FluidType}
	 * one. Macaw's Bridges feeds its blocks in with {@code accept(ItemLike)}, one of them has no item, and the throw
	 * propagated out of {@code CreativeModeTabs.buildAllTabContents} into
	 * {@code CreativeModeInventoryScreen.<init>} — so opening the creative menu at all crashed the client, and NO
	 * tab (vanilla or modded) was reachable.
	 *
	 * <p>Rewriting the throw to a {@code return} makes the wrapper drop that one entry and keep building, which is
	 * the MinecraftForge behaviour the mod was written against. Only the throw is replaced; the count==1 fast path
	 * is untouched, so well-formed stacks still take the normal route.
	 */
	static boolean tolerateEmptyCreativeTabStacks(ClassNode node) {
		if (!"net/neoforged/neoforge/event/EventHooks".equals(node.name) || node.methods == null) return false;

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!method.name.startsWith("lambda$onCreativeModeTabBuildContents$")) continue;
			changed |= MergedBaseGuiRepair.replaceStackCountThrowWithReturn(method);
		}
		if (!changed) return false;

		ForbricLog.warn("[Forbric/MergedBaseCompat] creative-tab output now SKIPS empty stacks instead of throwing "
				+ "— NeoForge won CreativeModeTab.buildContents on the merged base and its stricter contract was "
				+ "aborting the whole creative menu for MinecraftForge mods (Macaw's Bridges)");
		return true;
	}


	/** Replaces {@code throw new IllegalArgumentException("The stack count must be 1")} with a plain {@code return}. */
	static boolean replaceStackCountThrowWithReturn(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof LdcInsnNode ldc) || !ForbricMergedBaseCompatTransformer.STACK_COUNT_MESSAGE.equals(ldc.cst)) continue;

			AbstractInsnNode start = insn;
			while (start != null && !(start.getOpcode() == Opcodes.NEW && start instanceof TypeInsnNode type
					&& "java/lang/IllegalArgumentException".equals(type.desc))) {
				start = start.getPrevious();
			}
			AbstractInsnNode end = insn;
			while (end != null && end.getOpcode() != Opcodes.ATHROW) {
				end = end.getNext();
			}
			if (start == null || end == null) continue;

			// The whole new/dup/ldc/<init>/athrow run pushes and consumes only its own operands, so swapping it for a
			// RETURN leaves the stack exactly as the following frames already describe it.
			method.instructions.insertBefore(start, new MethodInsnNode(Opcodes.INVOKESTATIC,
					"net/forbric/kernel/boot/KernelLifecycle", "onCreativeTabEntrySkipped", "()V", false));
			method.instructions.insertBefore(start, new InsnNode(Opcodes.RETURN));
			for (AbstractInsnNode cur = start; cur != null;) {
				AbstractInsnNode next = cur == end ? null : cur.getNext();
				method.instructions.remove(cur);
				cur = next;
			}
			return true;
		}
		return false;
	}


	/**
	 * Posts NeoForge's {@code ItemTooltipEvent} beside MinecraftForge's, on the same list.
	 *
	 * <p>{@code getTooltipLines} carries exactly one event call and it is MinecraftForge's. NeoForge's event is
	 * never constructed, so a NeoForge mod that appends a tooltip line appends it to nothing — Architectury and
	 * RarityCore both do, and the only symptom either produced was a load-report row.
	 *
	 * <p>Inserted AFTER MinecraftForge's call rather than before, so each family sees the tooltip in the order its
	 * own loader gives it. The six arguments are read from the frame the call site already has: the stack is
	 * {@code this}, the player and flag are the ones MinecraftForge's call is loading, and the context and display
	 * are the method's first parameter and its display local — so a listener asking for either gets the real one.
	 */
	static boolean postNeoForgesItemTooltipEvent(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.ITEM_STACK.equals(node.name)) return false;

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!"getTooltipLines".equals(method.name)) continue;
			// Already posted: a second pass over a repaired class must leave it exactly as it is, or the event
			// fires twice and every NeoForge tooltip line appears twice.
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (insn instanceof MethodInsnNode done && ForbricMergedBaseCompatTransformer.TOOLTIP_BRIDGE.equals(done.owner)) return false;
			}
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
				if (!ForbricMergedBaseCompatTransformer.FORGE_EVENT_FACTORY.equals(call.owner) || !ForbricMergedBaseCompatTransformer.ON_ITEM_TOOLTIP.equals(call.name)) continue;

				// The four operands MinecraftForge's call is about to consume, in its own order, reconstructed from
				// the frame: this, player, list, flag. Their local slots are the ones the call site loads, so they
				// are read off the preceding loads rather than assumed.
				List<VarInsnNode> loads = MergedBaseGuiRepair.precedingLoads(insn, 4);
				if (loads.size() != 4) continue;
				VarInsnNode display = MergedBaseGuiRepair.displayLocal(method);
				if (display == null) continue;

				InsnList post = new InsnList();
				for (VarInsnNode load : loads) post.add(new VarInsnNode(Opcodes.ALOAD, load.var));
				post.add(new VarInsnNode(Opcodes.ALOAD, 1));
				post.add(new VarInsnNode(Opcodes.ALOAD, display.var));
				post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.TOOLTIP_BRIDGE, "postNeoForge",
						ForbricMergedBaseCompatTransformer.TOOLTIP_BRIDGE_DESC, false));
				// After the POP that discards MinecraftForge's returned event, so the stack is empty here.
				AbstractInsnNode after = insn.getNext();
				while (after != null && after.getOpcode() == Opcodes.POP) after = after.getNext();
				method.instructions.insertBefore(after != null ? after : insn.getNext(), post);
				changed = true;
				break;
			}
		}
		if (changed) {
			ForbricLog.info("[Forbric/MergedBaseCompat] %s.getTooltipLines now posts NeoForge's ItemTooltipEvent "
					+ "beside MinecraftForge's, on the same list — the merged body carries only MinecraftForge's "
					+ "call, so a NeoForge mod's tooltip lines went into a list nobody built", node.name);
		}
		return changed;
	}


	/** The {@code n} consecutive ALOADs immediately before {@code call}, in source order, or fewer. */
	static List<VarInsnNode> precedingLoads(AbstractInsnNode call, int n) {
		java.util.Deque<VarInsnNode> loads = new java.util.ArrayDeque<>();
		AbstractInsnNode cursor = call.getPrevious();
		while (cursor != null && loads.size() < n) {
			if (cursor.getOpcode() == Opcodes.ALOAD && cursor instanceof VarInsnNode load) loads.addFirst(load);
			else if (cursor.getOpcode() >= 0) break;
			cursor = cursor.getPrevious();
		}
		return new ArrayList<>(loads);
	}


	/** The {@code TooltipDisplay} local, by its declared type in the method's own variable table. */
	static VarInsnNode displayLocal(MethodNode method) {
		if (method.localVariables == null) return null;
		for (LocalVariableNode local : method.localVariables) {
			if ("Lnet/minecraft/world/item/component/TooltipDisplay;".equals(local.desc)) {
				return new VarInsnNode(Opcodes.ALOAD, local.index);
			}
		}
		return null;
	}


	/**
	 * Gives an item's attributes back to the mod that computes them — which is what elytra flight hangs off.
	 *
	 * <p>The merge split one mechanism down the middle. {@code LivingEntity.canGlide} came from NeoForge, and
	 * NeoForge's version does not look at the item at all: it asks whether the entity has the
	 * {@code neoforge:gliding_flight} attribute above zero. {@code ItemStack.forEachModifier} came from vanilla
	 * (Forge leaves it alone), and vanilla's version reads the raw {@code ATTRIBUTE_MODIFIERS} component. NeoForge's
	 * version calls {@code getAttributeModifiers()}, whose whole purpose is to post
	 * {@code ItemAttributeModifierEvent} — and {@code NeoForgeMod.onItemAttributeModifiers} is the ONLY thing
	 * anywhere that adds the gliding attribute, off the item's {@code minecraft:glider} component.
	 *
	 * <p>So the producer was on one side of the merge and the consumer on the other: the attribute is a
	 * {@code BooleanAttribute} defaulting to false, nothing ever raises it, {@code canGlide()} is permanently
	 * false, {@code tryToStartFallFlying} refuses and {@code updateFallFlying} clears the flag every tick. Elytra
	 * simply does not work, with no error anywhere.
	 *
	 * <p>The damage is wider than elytra — every mod that adds a modifier through that event was being ignored, and
	 * elytra is only the case vanilla itself routes through it. The repair points the read at NeoForge's computed
	 * answer: four instructions become one, same stack shape, no branch and no frame.
	 *
	 * <p>The merge-conflict report does not list this method. NeoForge's patch here is an unqualified call to a
	 * method on {@code ItemStack} itself — an interface default from {@code IItemStackExtension}, which the merged
	 * class still implements — so it names nothing under {@code net/neoforged/} for a detector to notice.
	 */
	static boolean askNeoForgeWhatAnItemsAttributesAre(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.ITEM_STACK.equals(node.name)) return false;
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!"forEachModifier".equals(method.name) || method.instructions == null) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode type) || type.getOpcode() != Opcodes.GETSTATIC
						|| !ForbricMergedBaseCompatTransformer.DATA_COMPONENTS.equals(type.owner) || !"ATTRIBUTE_MODIFIERS".equals(type.name)) {
					continue;
				}
				AbstractInsnNode empty = MergedBaseAsm.nextReal(type);
				AbstractInsnNode fetch = MergedBaseAsm.nextReal(empty);
				AbstractInsnNode cast = MergedBaseAsm.nextReal(fetch);
				// The exact vanilla shape and nothing else: getOrDefault(ATTRIBUTE_MODIFIERS, EMPTY) then a cast.
				if (!(empty instanceof FieldInsnNode e) || e.getOpcode() != Opcodes.GETSTATIC
						|| !ForbricMergedBaseCompatTransformer.ATTRIBUTE_MODIFIERS_TYPE.equals(e.owner) || !"EMPTY".equals(e.name)) {
					continue;
				}
				if (!(fetch instanceof MethodInsnNode f) || !"getOrDefault".equals(f.name)) continue;
				if (!(cast instanceof TypeInsnNode c) || c.getOpcode() != Opcodes.CHECKCAST
						|| !ForbricMergedBaseCompatTransformer.ATTRIBUTE_MODIFIERS_TYPE.equals(c.desc)) {
					continue;
				}
				AbstractInsnNode after = cast.getNext();
				method.instructions.insert(cast, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ForbricMergedBaseCompatTransformer.ITEM_STACK,
						ForbricMergedBaseCompatTransformer.NEO_ATTRIBUTES, "()L" + ForbricMergedBaseCompatTransformer.ATTRIBUTE_MODIFIERS_TYPE + ";", false));
				for (AbstractInsnNode dead : new AbstractInsnNode[] { type, empty, fetch, cast }) {
					method.instructions.remove(dead);
				}
				insn = after == null ? method.instructions.getLast() : after;
				changed = true;
			}
		}
		if (changed) {
			ForbricLog.info("[Forbric/MergedBaseCompat] ItemStack.forEachModifier now asks NeoForge what an item's "
					+ "attributes are instead of reading the raw component — the merge took NeoForge's canGlide, which "
					+ "reads an attribute only NeoForge's ItemAttributeModifierEvent ever sets, and vanilla's reader, "
					+ "which never posts it. Elytra flight was the visible half of that");
		}
		return changed;
	}

	/**
	 * Sends the burn-time question through the kernel so both ecosystems answer it.
	 *
	 * <p>The merged {@code FuelValues.burnDuration} calls MinecraftForge's {@code getItemBurnTime} and nothing
	 * else, so NeoForge's {@code FurnaceFuelBurnTimeEvent} is never posted — measured, and {@code balm} in the
	 * test pack subscribes to it. This is the reverse of every bridge in this tree, where NeoForge won and
	 * MinecraftForge is re-emitted, and it cannot be fixed by a listener: NeoForge's side is a static call, not
	 * something to subscribe to.
	 *
	 * <p>NeoForge's hook needs the {@code FuelValues} instance, which MinecraftForge's three-argument shape does
	 * not carry, so the receiver is pushed before the call and the descriptor widened. Only in INSTANCE methods:
	 * in a static one, slot 0 is the first parameter and pushing it would hand NeoForge an ItemStack typed as a
	 * FuelValues.
	 */
	static boolean letBothEcosystemsSetBurnTime(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.FUEL_VALUES.equals(node.name)) return false;
		int redirected = 0;
		for (MethodNode method : node.methods) {
			if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !"net/minecraftforge/event/ForgeEventFactory".equals(call.owner)
						|| !"getItemBurnTime".equals(call.name)
						|| !ForbricMergedBaseCompatTransformer.FORGE_BURN_TIME_DESC.equals(call.desc)) {
					continue;
				}
				method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
				call.owner = ForbricMergedBaseCompatTransformer.KERNEL_FUEL_VALUES;
				call.name = "burnDuration";
				call.desc = ForbricMergedBaseCompatTransformer.KERNEL_BURN_TIME_DESC;
				method.maxStack = Math.max(method.maxStack, 5);
				redirected++;
			}
		}
		if (redirected == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] FuelValues now asks both ecosystems how long something burns "
				+ "(%d call site(s)) — the merge kept only MinecraftForge's hook, so NeoForge's "
				+ "FurnaceFuelBurnTimeEvent was posted nowhere", redirected);
		return true;
	}
}
