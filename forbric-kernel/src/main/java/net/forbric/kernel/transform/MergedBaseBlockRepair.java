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
 * Block appearance and block-state model methods the merge dropped.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseBlockRepair {
	private MergedBaseBlockRepair() {
	}


	static boolean addBlockAppearanceResolver(ClassNode node) {
		String desc = "(Lnet/minecraft/world/level/block/state/BlockState;"
				+ "Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;"
				+ "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;";
		if (MergedBaseAsm.hasMethod(node, "getAppearance", desc)) return false;

		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "getAppearance", desc, null, null);
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		method.instructions.add(new InsnNode(Opcodes.ARETURN));
		method.maxStack = 1;
		method.maxLocals = 7;
		node.methods.add(method);

		ForbricLog.warn("[Forbric/MergedBaseCompat] gave Block its own getAppearance — NeoForge's and fabric-api's "
				+ "interfaces both default it identically and neither wins, so every block subclass that does not "
				+ "override it died on IncompatibleClassChangeError the moment a connected-texture mod asked what "
				+ "a neighbour looks like");
		return true;
	}


	static boolean addBlockStateAppearanceResolver(ClassNode node) {
		if ("net/minecraft/world/level/block/Block".equals(node.name)) return MergedBaseBlockRepair.addBlockAppearanceResolver(node);
		if (!"net/minecraft/world/level/block/state/BlockState".equals(node.name)) return false;

		String desc = "(Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;"
				+ "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;";
		if (MergedBaseAsm.hasMethod(node, "getAppearance", desc)) return false;

		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "getAppearance", desc, null, null);
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
				"net/minecraft/world/level/block/state/BlockState", "getBlock",
				"()Lnet/minecraft/world/level/block/Block;", false));
		for (int slot = 0; slot <= 5; slot++) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
				"net/minecraft/world/level/block/Block", "getAppearance",
				"(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndLightGetter;"
						+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)"
						+ "Lnet/minecraft/world/level/block/state/BlockState;", false));
		method.instructions.add(new InsnNode(Opcodes.ARETURN));
		// receiver + the six arguments of Block.getAppearance
		method.maxStack = 7;
		method.maxLocals = 6;
		node.methods.add(method);

		ForbricLog.warn("[Forbric/MergedBaseCompat] gave BlockState its own getAppearance — NeoForge's and "
				+ "fabric-api's interfaces both default it with the same descriptor and neither wins, so the "
				+ "first mod to ask a neighbour what it looks like (a connected-texture mod) died on "
				+ "IncompatibleClassChangeError. Both defaults are the same call, so this is that call");
		return true;
	}


	static boolean addBlockStateModelConflictResolvers(ClassNode node) {
		if (!"net/minecraft/client/renderer/block/dispatch/BlockStateModel".equals(node.name)) return false;

		boolean changed = false;
		if (!MergedBaseAsm.hasMethod(node, "createGeometryKey",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)"
						+ "Ljava/lang/Object;")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "createGeometryKey",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)"
							+ "Ljava/lang/Object;",
					null, null);
			method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			method.maxStack = 1;
			method.maxLocals = 5;
			node.methods.add(method);
			changed = true;
		}

		if (!MergedBaseAsm.hasMethod(node, "particleMaterial",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;)"
						+ "Lnet/minecraft/client/resources/model/sprite/Material$Baked;")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "particleMaterial",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)"
							+ "Lnet/minecraft/client/resources/model/sprite/Material$Baked;",
					null, null);
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
					"net/minecraft/client/renderer/block/dispatch/BlockStateModel",
					"particleMaterial",
					"()Lnet/minecraft/client/resources/model/sprite/Material$Baked;",
					true));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			method.maxStack = 1;
			method.maxLocals = 4;
			node.methods.add(method);
			changed = true;
		}

		if (!MergedBaseAsm.hasMethod(node, "materialFlags",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;)I")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "materialFlags",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)I",
					null, null);
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
					"net/minecraft/client/renderer/block/dispatch/BlockStateModel",
					"materialFlags", "()I", true));
			method.instructions.add(new InsnNode(Opcodes.IRETURN));
			method.maxStack = 1;
			method.maxLocals = 4;
			node.methods.add(method);
			changed = true;
		}

		if (changed) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] added BlockStateModel default-method conflict resolvers");
		}
		return changed;
	}
}
