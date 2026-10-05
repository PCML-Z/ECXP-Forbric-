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
 * Client init, geometry reload, reload-listener names, and the window-title brand.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseClientRepair {
	private MergedBaseClientRepair() {
	}


	/**
	 * Makes {@code DefaultAttributes} read BOTH ecosystems' mod-attribute maps, not just the one that won the merge.
	 *
	 * <p>Both families collect a mod's entity attributes into a map of their own —
	 * {@code ForgeHooks.FORGE_ATTRIBUTES} and NeoForge's {@code CommonHooks} equivalent — and vanilla's
	 * {@code DefaultAttributes} is the single consumer both patch. The merge keeps one patch, and it kept
	 * NeoForge's: {@code javap} of the merged class shows {@code getSupplier} and {@code hasSupplier} each calling
	 * {@code CommonHooks.getAttributesView()}, and a constant-pool scan of the whole merged base finds
	 * {@code EntityAttributeCreationEvent} named nowhere.
	 *
	 * <p>So a traditional MinecraftForge mod's attributes went into a map with no reader — the producer/consumer
	 * split this project has hit at field level before, here at method level. An {@code AttributeSupplier} is what
	 * gives a living entity its health and movement and an entity without one is refused, so it is not a
	 * degradation: {@code cursed_breeding} logged "has no attributes" 348 times in one boot and its mobs could not
	 * exist.
	 *
	 * <p>Both call sites take no arguments and return {@code Map}, so each is an owner/name replacement on one
	 * instruction with nothing on the stack moved.
	 */
	static boolean serveDefaultAttributesBothEcosystems(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.DEFAULT_ATTRIBUTES.equals(node.name)) return false;
		int redirected = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !ForbricMergedBaseCompatTransformer.NEO_COMMON_HOOKS.equals(call.owner) || !"getAttributesView".equals(call.name)
						|| !ForbricMergedBaseCompatTransformer.ATTRIBUTES_VIEW.equals(call.desc)) {
					continue;
				}
				call.owner = ForbricMergedBaseCompatTransformer.KERNEL_FORGE_ATTRIBUTES;
				call.name = "attributesView";
				redirected++;
			}
		}
		if (redirected == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] DefaultAttributes now reads both ecosystems' mod-attribute maps "
				+ "(%d call site(s)) — the merge kept only NeoForge's reader, so a traditional MinecraftForge mod's "
				+ "entities had no attributes and could not exist", redirected);
		return true;
	}


	static boolean restoreForgeClientInit(ClassNode node) {
		if (!"net/minecraft/client/Minecraft".equals(node.name)
				|| "off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) return false;
		String owner = ForeignType.CLIENT_HOOKS.internal(Ecosystem.NEOFORGE);
		String target = "net/forbric/kernel/runtime/KernelForgeClientInit";
		String init = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V";
		String particles = "(Lnet/minecraft/client/particle/ParticleResources;)V";
		List<MethodInsnNode> matches = new java.util.ArrayList<>();
		MethodNode constructor = null;
		int initializers = 0, providers = 0;
		for (MethodNode method : node.methods) {
			if (!"<init>".equals(method.name)) continue;
			for (AbstractInsnNode instruction : method.instructions) {
				if (!(instruction instanceof MethodInsnNode call)) continue;
				if (!"initClientHooks".equals(call.name) && !"onRegisterParticleProviders".equals(call.name)) continue;
				if (target.equals(call.owner)) return false;
				if (!owner.equals(call.owner)) continue;
				if (call.getOpcode() != Opcodes.INVOKESTATIC || call.itf) return false;
				if ("initClientHooks".equals(call.name) && init.equals(call.desc)) initializers++;
				else if ("onRegisterParticleProviders".equals(call.name) && particles.equals(call.desc)) providers++;
				else return false;
				if (constructor != null && constructor != method) return false;
				constructor = method;
				matches.add(call);
			}
		}
		if (initializers != 1 || providers != 1) return false;
		for (MethodInsnNode call : matches) call.owner = target;
		// Both sites land or neither does (the checks above are whole-or-nothing), so both bridges are recorded
		// here; EventBridges.verify(CLIENT_INIT) names them at the client setup hook if this repair stood down.
		EventBridges.installed(GameEventBridge.CLIENT_INIT_HOOKS);
		EventBridges.installed(GameEventBridge.PARTICLE_PROVIDERS);
		ForbricLog.info("[Forbric/MergedBaseCompat] Minecraft now initializes both Forge families' client hooks and particles");
		return true;
	}


	static boolean restoreForgeGeometryReload(ClassNode node) {
		if (!"net/minecraft/client/resources/model/ModelManager".equals(node.name)
				|| "off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) return false;
		String desc = "(Lnet/minecraft/server/packs/resources/PreparableReloadListener$SharedState;Ljava/util/concurrent/Executor;"
				+ "Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;";
		MethodNode method = MergedBaseAsm.findMethod(node, "reload", desc);
		if (method == null || (method.access & Opcodes.ACC_STATIC) != 0) return false;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call
					&& (("net/forbric/kernel/runtime/KernelForgeClientInit".equals(call.owner)
							&& "initGeometryLoaders".equals(call.name))
						|| ("net/minecraftforge/client/model/geometry/GeometryLoaderManager".equals(call.owner)
							&& "init".equals(call.name)))) return false;
		}
		AbstractInsnNode first = method.instructions.getFirst();
		while (first != null && first.getOpcode() < 0) first = first.getNext();
		if (!(first instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 1) return false;
		AbstractInsnNode next = first.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		if (!(next instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
				|| !"net/minecraft/server/packs/resources/PreparableReloadListener$SharedState".equals(call.owner)
				|| !"resourceManager".equals(call.name)
				|| !"()Lnet/minecraft/server/packs/resources/ResourceManager;".equals(call.desc)) return false;
		method.instructions.insertBefore(first, new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/forbric/kernel/runtime/KernelForgeClientInit", "initGeometryLoaders", "()V", false));
		ForbricLog.info("[Forbric/MergedBaseCompat] ModelManager initializes Forge geometry loaders on every resource reload");
		return true;
	}


	/**
	 * Lets a Fabric mod add a client reload listener the way Fabric mods always have, without killing the client.
	 *
	 * <p>{@code AddClientReloadListenersEvent.lookupName} names each listener already in the resource manager by
	 * asking {@code VanillaClientListeners.getNameForClass}, and when that returns null it THROWS: "A non-vanilla
	 * reload listener … was added via mixin before the AddClientReloadListenerEvent!". The assertion is written
	 * for an instance whose only mods are NeoForge mods. Adding a listener by mixin is ordinary Fabric practice —
	 * there is no event for it to go through — so on a tri-ecosystem instance it fires on CORRECT mod code, from
	 * inside {@code ClientHooks.initClientHooks}, which runs inside {@code Minecraft.<init>}: vistas took the whole
	 * client down before it drew a frame.
	 *
	 * <p>The name is a sort key and a registry key and nothing else, so a synthesised one leaves the listener
	 * registered, sorted and RUNNING — which is the difference between this and swallowing the exception. Only
	 * the lookup inside this event is redirected: NeoForge's own {@code ClientNeoForgeMod} asks the same method
	 * about its own listeners, and those are in the table.
	 *
	 * <p>One instruction: same opcode, same descriptor, same stack.
	 */
	static boolean nameTheReloadListenersNeoForgeRefusesToName(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.ADD_CLIENT_RELOAD_LISTENERS.equals(node.name)) return false;
		int redirected = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !ForbricMergedBaseCompatTransformer.VANILLA_CLIENT_LISTENERS.equals(call.owner)
						|| !"getNameForClass".equals(call.name) || !ForbricMergedBaseCompatTransformer.NAME_FOR_CLASS.equals(call.desc)) {
					continue;
				}
				call.owner = ForbricMergedBaseCompatTransformer.KERNEL_RELOAD_NAMES;
				call.name = "nameFor";
				redirected++;
			}
		}
		if (redirected == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] a client reload listener NeoForge cannot name is now given one "
				+ "(%d lookup(s) redirected) — it used to throw inside Minecraft.<init> over a Fabric mod adding a "
				+ "listener by mixin, which is how Fabric mods have always added them", redirected);
		return true;
	}


	static boolean dropTheWindowTitlesLoaderBrand(ClassNode node) {
		if (!"net/minecraft/client/Minecraft".equals(node.name)) return false;
		MethodNode createTitle = MergedBaseAsm.findMethod(node, "createTitle", "()Ljava/lang/String;");
		if (createTitle == null) return false;

		for (AbstractInsnNode insn = createTitle.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof LdcInsnNode brand) || !ForbricMergedBaseCompatTransformer.LOADER_BRANDS.contains(brand.cst)) continue;
			AbstractInsnNode appendBrand = insn.getNext();
			if (!MergedBaseClientRepair.isStringBuilderAppend(appendBrand, "(Ljava/lang/String;)Ljava/lang/StringBuilder;")) continue;
			// The separator the brand arrives with: BIPUSH ' '; append(char). Without it the shape is not the one
			// this fixup was written for.
			AbstractInsnNode appendSpace = MergedBaseAsm.previousRealInsn(insn);
			AbstractInsnNode space = MergedBaseAsm.previousRealInsn(appendSpace);
			if (!MergedBaseClientRepair.isStringBuilderAppend(appendSpace, "(C)Ljava/lang/StringBuilder;")
					|| space == null || space.getOpcode() != Opcodes.BIPUSH
					|| ((org.objectweb.asm.tree.IntInsnNode) space).operand != ' ') {
				continue;
			}
			for (AbstractInsnNode dead : new AbstractInsnNode[] {space, appendSpace, insn, appendBrand}) {
				createTitle.instructions.remove(dead);
			}
			ForbricLog.info("[Forbric/MergedBaseCompat] took \"%s\" out of the window title — the merged base carries "
					+ "one loader's title patch, and this instance runs all three ecosystems", brand.cst);
			return true;
		}
		return false;
	}


	static boolean isStringBuilderAppend(AbstractInsnNode insn, String desc) {
		return insn instanceof MethodInsnNode call && "java/lang/StringBuilder".equals(call.owner)
				&& "append".equals(call.name) && desc.equals(call.desc);
	}
}
