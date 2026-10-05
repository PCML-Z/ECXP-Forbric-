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
 * Shared bytecode walks used by the merged-base repairs. No repair of its own.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseAsm {
	private MergedBaseAsm() {
	}


	/** The first instruction at or after {@code from} that is not a label, line number or frame. */
	static AbstractInsnNode realAfter(AbstractInsnNode from, boolean inclusive) {
		AbstractInsnNode insn = inclusive ? from : (from == null ? null : from.getNext());
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}


	/** Whether anything in {@code node} assigns the static field {@code name}. */
	static boolean writesStatic(ClassNode node, String name) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
						&& node.name.equals(field.owner) && name.equals(field.name)) {
					return true;
				}
			}
		}
		return false;
	}


	/** {@code this.<neo> = convert(this.<forge>)}, or nothing when either field is absent. */
	static InsnList field(ClassNode node, String name, String fromDesc, String toDesc, String converter) {
		InsnList out = new InsnList();
		if (!MergedBaseAsm.hasField(node, name, fromDesc) || !MergedBaseAsm.hasField(node, name, toDesc)) return out;
		out.add(new VarInsnNode(Opcodes.ALOAD, 0));
		out.add(new VarInsnNode(Opcodes.ALOAD, 0));
		out.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, name, fromDesc));
		out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.KERNEL_KEYS, converter,
				"(Ljava/lang/Object;)Ljava/lang/Object;", false));
		out.add(new TypeInsnNode(Opcodes.CHECKCAST, MergedBaseAsm.internal(toDesc)));
		out.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, name, toDesc));
		return out;
	}


	/** {@code Lsome/Type;} to {@code some/Type}. */
	static String internal(String descriptor) {
		return descriptor.substring(1, descriptor.length() - 1);
	}


	static boolean hasMethod(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return true;
		}
		return false;
	}


	static boolean writesField(MethodNode method, String fieldName) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.PUTFIELD && insn instanceof FieldInsnNode field && fieldName.equals(field.name)) {
				return true;
			}
		}
		return false;
	}


	static AbstractInsnNode nextReal(AbstractInsnNode cursor) {
		AbstractInsnNode next = cursor == null ? null : cursor.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}


	static AbstractInsnNode previousRealInsn(AbstractInsnNode from) {
		if (from == null) return null;
		for (AbstractInsnNode insn = from.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn.getOpcode() >= 0) return insn;
		}
		return null;
	}


	static MethodNode findMethod(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method;
		}
		return null;
	}


	static boolean hasField(ClassNode node, String name, String desc) {
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)) return true;
		}
		return false;
	}


	static boolean initializesStaticField(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (!method.name.equals("<clinit>") || !method.desc.equals("()V")) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
						&& field.owner.equals(node.name) && field.name.equals(name) && field.desc.equals(desc)) {
					return true;
				}
			}
		}
		return false;
	}


	static AbstractInsnNode previousReal(AbstractInsnNode cursor) {
		while (cursor != null && cursor.getOpcode() < 0) {
			cursor = cursor.getPrevious();
		}
		return cursor;
	}


	static FieldNode findField(ClassNode node, String name) {
		if (node.fields == null) return null;
		for (FieldNode field : node.fields) {
			if (field.name.equals(name)) return field;
		}
		return null;
	}


	/** First method with this name, whatever its descriptor — distinct from {@link #findMethod(ClassNode,String,String)}. */
	static MethodNode findMethodByName(ClassNode node, String name) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name)) return method;
		}
		return null;
	}
}
