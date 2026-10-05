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
 * Picture-in-picture renderers, and the missing MinecraftForge model-data manager.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseRenderRepair {
	private MergedBaseRenderRepair() {
	}


	/**
	 * Stops the block-breaking overlay from crashing the render frame.
	 *
	 * <p>{@code LevelExtractor.extractBlockDestroyAnimation} asks the level for MinecraftForge's
	 * {@code ModelDataManager} and dereferences it without a check. On a single-ecosystem base that is safe, because
	 * Forge's own {@code ClientLevel} patch overrides the accessor; on the merged base NeoForge's override won, and
	 * because the two return different types it does not override Forge's at all — so the call lands on Forge's
	 * interface default, whose whole body is {@code return null}. Every frame drawn while any block is being broken
	 * then dies with "Description: Render Frame", which is why this only showed up once, in a run where a break
	 * animation happened to be on screen.
	 *
	 * <p>There is nothing to route it to: no path on this base ever builds a Forge-typed manager, so no Forge-typed
	 * model data exists to find. The call therefore becomes the value Forge's own lookup returns for a position it
	 * is not tracking — {@code ModelData.EMPTY} — which is what the overlay would have drawn with anyway. A mod's
	 * dynamic model data still reaches the block itself through NeoForge's manager, which the level does have; only
	 * the break overlay draws with defaults.
	 */
	static boolean surviveTheMissingForgeModelDataManager(ClassNode node) {
		if (!"net/minecraft/client/renderer/extract/LevelExtractor".equals(node.name)) return false;

		boolean changed = false;
		for (MethodNode m : node.methods) {
			List<MethodInsnNode> lookups = new ArrayList<>();
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL && insn instanceof MethodInsnNode call
						&& ForbricMergedBaseCompatTransformer.FORGE_MODEL_DATA_MANAGER.equals(call.owner) && "getAtOrEmpty".equals(call.name)) {
					lookups.add(call);
				}
			}
			for (MethodInsnNode lookup : lookups) {
				// The receiver expression, exactly: ALOAD this; GETFIELD level; INVOKEVIRTUAL getModelDataManager;
				// then the position argument. Anything else means the method was rewritten upstream — leave it be.
				AbstractInsnNode pos = MergedBaseAsm.previousRealInsn(lookup);
				AbstractInsnNode manager = MergedBaseAsm.previousRealInsn(pos);
				AbstractInsnNode level = MergedBaseAsm.previousRealInsn(manager);
				AbstractInsnNode self = MergedBaseAsm.previousRealInsn(level);
				if (pos == null || pos.getOpcode() != Opcodes.ALOAD
						|| !(manager instanceof MethodInsnNode get) || !"getModelDataManager".equals(get.name)
						|| level == null || level.getOpcode() != Opcodes.GETFIELD
						|| self == null || self.getOpcode() != Opcodes.ALOAD) {
					continue;
				}
				// The constant goes in where the receiver expression began, BEFORE the five are unlinked: a removed
				// node's neighbours are no longer a usable anchor.
				m.instructions.insertBefore(self,
						new FieldInsnNode(Opcodes.GETSTATIC, ForbricMergedBaseCompatTransformer.FORGE_MODEL_DATA, "EMPTY", "L" + ForbricMergedBaseCompatTransformer.FORGE_MODEL_DATA + ";"));
				for (AbstractInsnNode dead : new AbstractInsnNode[] {self, level, manager, pos, lookup}) {
					m.instructions.remove(dead);
				}
				changed = true;
			}
		}
		if (!changed) return false;
		ForbricLog.warn("[Forbric/MergedBaseCompat] the block-breaking overlay no longer asks for MinecraftForge's "
				+ "model-data manager — NeoForge won the level's accessor, so Forge's returned null and every frame "
				+ "drawn while a block was being broken crashed the game");
		return true;
	}


	/**
	 * Makes a picture-in-picture renderer registered the VANILLA way draw again, by giving NeoForge's pooled lookup
	 * a fallback to the map the merge orphaned.
	 *
	 * <p>{@code GuiRenderer} ends up with BOTH ecosystems' versions of the same job:
	 *
	 * <ul>
	 *   <li>{@code preparePictureInPictureState(T, int)} — vanilla's. Reads {@code pictureInPictureRenderers}, a
	 *       {@code Class -> PictureInPictureRenderer} map, and calls {@code prepare} on the one it finds. <b>Nothing
	 *       calls it.</b></li>
	 *   <li>{@code preparePictureInPictureState(T, int, boolean)} — NeoForge's, and the one {@code render()} calls.
	 *       Reads {@code pictureInPictureRendererPools} instead, and returns false for a state class with no pool.</li>
	 * </ul>
	 *
	 * <p>Every guest mod registers into the first map, because that is the only one vanilla has: Xaero's Minimap puts
	 * its {@code MinimapPipRenderer} there, malilib its block-state element renderer. Both then draw nothing at all —
	 * no exception, no log, the element is simply absent. Chasing it from the symptom is brutal, because every link
	 * before this one is intact: the mixins apply, the hooks are called every frame, the mod's own state is live. The
	 * lookup misses one map over.
	 *
	 * <p>So the null-pool branch now falls through to the orphaned map instead of returning false. Guest renderers get
	 * exactly vanilla's contract — one instance per state class, {@code prepare} called directly — and NeoForge's
	 * pooled renderers are untouched, which matters: a pool CLOSES the renderers a frame did not use, so handing a
	 * guest's single long-lived instance to one would free its GL target out from under it.
	 *
	 * <p>The bridge method is synthesized from the descriptors of the orphaned overload itself rather than from
	 * hard-coded names, so it stays correct if the merge shifts.
	 */
	static boolean bridgeOrphanedPipRenderers(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.GUI_RENDERER.equals(node.name)) return false;
		if (MergedBaseAsm.findField(node, ForbricMergedBaseCompatTransformer.PIP_RENDERERS) == null || MergedBaseAsm.findField(node, ForbricMergedBaseCompatTransformer.PIP_POOLS) == null) return false;
		if (MergedBaseAsm.findMethodByName(node, ForbricMergedBaseCompatTransformer.PIP_BRIDGE) != null) return false;

		MethodNode orphaned = null;
		MethodNode live = null;
		for (MethodNode method : node.methods) {
			if (!ForbricMergedBaseCompatTransformer.PIP_PREPARE.equals(method.name)) continue;
			if (Type.getReturnType(method.desc).getSort() == Type.BOOLEAN) live = method; else orphaned = method;
		}
		if (orphaned == null || live == null) return false;

		// One source for the state type: the CALL SITE's. Deriving the bridge's descriptor from the orphaned overload
		// instead would let the two drift apart if a future merge narrows one of them, and the only symptom would be a
		// NoSuchMethodError on the first frame that actually reaches an orphaned renderer.
		String stateDesc = Type.getArgumentTypes(live.desc)[0].getDescriptor();
		MethodNode bridge = MergedBaseRenderRepair.buildPipBridge(node, orphaned, stateDesc);
		if (bridge == null || !MergedBaseRenderRepair.redirectMissingPoolToBridge(node, live, stateDesc)) return false;

		node.methods.add(bridge);
		boolean filled = MergedBaseRenderRepair.fillOrphanedPipMap(node, MergedBaseAsm.findField(node, ForbricMergedBaseCompatTransformer.PIP_RENDERERS));
		ForbricLog.warn("[Forbric/MergedBaseCompat] gave GuiRenderer's pooled picture-in-picture lookup a fallback to "
				+ "the orphaned vanilla map — NeoForge won preparePictureInPictureState, so every guest-registered "
				+ "GUI element (Xaero's minimap, malilib's overlays) was registered where nothing reads%s",
				filled ? ", and gave that map its only writer" : "");
		return true;
	}


	/**
	 * Assigns the orphaned map in {@code GuiRenderer.<init>}, from MinecraftForge's registration event.
	 *
	 * <p>The field is declared, read in one place, and <b>written nowhere</b>: NeoForge's constructor won the byte
	 * merge and fills its pooled map instead, so vanilla's plain one stays null. That is two failures in one. A
	 * MinecraftForge mod's picture-in-picture renderer has nothing to register into, because the event that would
	 * have filled this map is posted by nobody; and the fallback above reads the map WITHOUT a null check, so the
	 * first frame reaching a state class with no pool would throw inside the game's own render loop.
	 *
	 * <p>Appended before each RETURN of the constructor, which is where a final field may still be assigned.
	 */
	static boolean fillOrphanedPipMap(ClassNode node, FieldNode renderers) {
		if (renderers == null) return false;
		MethodNode init = null;
		for (MethodNode method : node.methods) {
			if ("<init>".equals(method.name)) init = method;
		}
		if (init == null) return false;
		for (AbstractInsnNode insn : init.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && ForbricMergedBaseCompatTransformer.PIP_BUILDER_OWNER.equals(call.owner)) return false;
		}
		int listSlot = -1;
		int slot = 1;
		for (Type argument : Type.getArgumentTypes(init.desc)) {
			if ("Ljava/util/List;".equals(argument.getDescriptor())) listSlot = slot;
			slot += argument.getSize();
		}
		if (listSlot < 0) return false;
		// Both constructors erase to the same descriptor. Guest mixins can append ordinary renderers
		// to NeoForge's registration list, so filter only the pool's input and retain the original list.
		for (AbstractInsnNode insn : init.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && "createPools".equals(call.name)
					&& "net/neoforged/neoforge/client/gui/PictureInPictureRendererPool".equals(call.owner)
					&& "(Ljava/util/List;)Ljava/util/Map;".equals(call.desc)) {
				init.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.PIP_BUILDER_OWNER,
						"poolRegistrations", "(Ljava/util/List;)Ljava/util/List;", false));
			}
		}

		int appended = 0;
		for (AbstractInsnNode insn : init.instructions.toArray()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			InsnList assign = new InsnList();
			assign.add(new VarInsnNode(Opcodes.ALOAD, 0));
			assign.add(new VarInsnNode(Opcodes.ALOAD, listSlot));
			assign.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.PIP_BUILDER_OWNER, "build", "(Ljava/util/List;)Ljava/util/Map;",
					false));
			assign.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, renderers.name, renderers.desc));
			init.instructions.insertBefore(insn, assign);
			appended++;
		}
		if (appended == 0) return false;
		init.maxStack = Math.max(init.maxStack, 2);
		for (MethodNode method : node.methods) {
			if (!"close".equals(method.name) || !"()V".equals(method.desc)) continue;
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (insn.getOpcode() != Opcodes.RETURN) continue;
				InsnList close = new InsnList();
				close.add(new VarInsnNode(Opcodes.ALOAD, 0));
				close.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, renderers.name, renderers.desc));
				close.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.PIP_BUILDER_OWNER, "close", "(Ljava/util/Map;)V", false));
				method.instructions.insertBefore(insn, close);
			}
			method.maxStack = Math.max(method.maxStack, 1);
		}
		return true;
	}


	/**
	 * Builds {@code boolean forbric$prepareOrphanedPip(state, i)} — vanilla's lookup, with a boolean saying whether
	 * it found anything. Every field and call is cloned out of the orphaned overload, so nothing here is spelled twice;
	 * {@code stateDesc} comes from the CALL SITE so the two cannot disagree.
	 */
	static MethodNode buildPipBridge(ClassNode node, MethodNode orphaned, String stateDesc) {
		FieldInsnNode renderers = null;
		FieldInsnNode renderState = null;
		FieldInsnNode dispatcher = null;
		TypeInsnNode rendererCast = null;
		MethodInsnNode mapGet = null;
		MethodInsnNode prepare = null;

		for (AbstractInsnNode insn = orphaned.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
				if (ForbricMergedBaseCompatTransformer.PIP_RENDERERS.equals(field.name)) renderers = field;
				else if (renderState == null) renderState = field;
				else if (dispatcher == null) dispatcher = field;
			} else if (insn instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
				rendererCast = cast;
			} else if (insn instanceof MethodInsnNode call) {
				if ("get".equals(call.name)) mapGet = call;
				else if ("prepare".equals(call.name)) prepare = call;
			}
		}
		if (renderers == null || renderState == null || dispatcher == null
				|| rendererCast == null || mapGet == null || prepare == null) {
			ForbricLog.debug("[Forbric/MergedBaseCompat] GuiRenderer's orphaned pip overload has an unexpected shape "
					+ "— leaving the pooled lookup alone");
			return null;
		}

		MethodNode bridge = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
				ForbricMergedBaseCompatTransformer.PIP_BRIDGE, "(" + stateDesc + "I)Z", null, null);
		LabelNode miss = new LabelNode();
		InsnList code = bridge.instructions;

		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, renderers.name, renderers.desc));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		// Object.getClass rather than the interface's, so this holds however the state type is declared.
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;",
				false));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, mapGet.owner, mapGet.name, mapGet.desc, true));
		code.add(new TypeInsnNode(Opcodes.CHECKCAST, rendererCast.desc));
		code.add(new VarInsnNode(Opcodes.ASTORE, 3));
		code.add(new VarInsnNode(Opcodes.ALOAD, 3));
		code.add(new JumpInsnNode(Opcodes.IFNULL, miss));

		code.add(new VarInsnNode(Opcodes.ALOAD, 3));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, renderState.name, renderState.desc));
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, dispatcher.name, dispatcher.desc));
		code.add(new VarInsnNode(Opcodes.ILOAD, 2));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, prepare.owner, prepare.name, prepare.desc, false));
		code.add(new InsnNode(Opcodes.ICONST_1));
		code.add(new InsnNode(Opcodes.IRETURN));

		code.add(miss);
		// Both paths reach here with slot 3 holding the (null) renderer, so the frame simply appends it.
		code.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] {rendererCast.desc}, 0, null));
		code.add(new InsnNode(Opcodes.ICONST_0));
		code.add(new InsnNode(Opcodes.IRETURN));

		bridge.maxStack = 5;
		bridge.maxLocals = 4;
		return bridge;
	}


	/** Rewrites the live overload's "no pool for this state class" early return into a call to the bridge. */
	static boolean redirectMissingPoolToBridge(ClassNode node, MethodNode live, String stateDesc) {
		for (AbstractInsnNode insn = live.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
					|| !ForbricMergedBaseCompatTransformer.PIP_POOLS.equals(field.name)) {
				continue;
			}

			AbstractInsnNode jump = insn;
			while (jump != null && !(jump instanceof JumpInsnNode)) jump = jump.getNext();
			if (jump == null || jump.getOpcode() != Opcodes.IFNONNULL) break;

			AbstractInsnNode falsy = jump.getNext();
			while (falsy != null && falsy.getOpcode() == -1) falsy = falsy.getNext();   // labels / line numbers
			if (falsy == null || falsy.getOpcode() != Opcodes.ICONST_0) break;

			AbstractInsnNode ret = falsy.getNext();
			while (ret != null && ret.getOpcode() == -1) ret = ret.getNext();
			if (ret == null || ret.getOpcode() != Opcodes.IRETURN) break;

			InsnList call = new InsnList();
			call.add(new VarInsnNode(Opcodes.ALOAD, 0));
			call.add(new VarInsnNode(Opcodes.ALOAD, 1));
			call.add(new VarInsnNode(Opcodes.ILOAD, 2));
			call.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, ForbricMergedBaseCompatTransformer.PIP_BRIDGE, "(" + stateDesc + "I)Z", false));
			live.instructions.insertBefore(falsy, call);
			live.instructions.remove(falsy);
			live.maxStack = Math.max(live.maxStack, 3);
			return true;
		}

		ForbricLog.debug("[Forbric/MergedBaseCompat] GuiRenderer's pooled pip lookup has an unexpected shape "
				+ "— leaving it alone");
		return false;
	}
}
