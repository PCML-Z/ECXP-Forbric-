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
 * Calls the merged base still makes to the previous loader's interop class names.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseLegacyNames {
	private MergedBaseLegacyNames() {
	}


	/**
	 * Rewrites calls the merged base makes to the kernel's reflective interop hooks under their OLD owner names.
	 *
	 * <p>The merged base is built by the previous-generation loader's {@code MergedBaseBuilder}, which splices an
	 * {@code INVOKESTATIC net/forbric/loader/impl/compat/ForbricCustomPayloadInterop.findCodec} into the merged
	 * {@code CustomPacketPayload} codec provider. Those three helper classes now live in the kernel
	 * ({@code net.forbric.kernel.interop}) and the old loader jars are no longer on the boot classpath, so the
	 * baked-in owner names no longer resolve — the symptom is a {@code NoClassDefFoundError} inside the netty
	 * encoder the moment anything sends a custom payload, i.e. every world join.
	 *
	 * <p>This is a permanent adaptation, not a one-off migration step: the base-building pipeline belongs to the
	 * other repository and keeps emitting the names it knows. The kernel owns what its own base links against, so
	 * it retargets them here rather than requiring a 35 MB artifact to be rebuilt in lockstep.
	 *
	 * <p>The only shape the builder emits is a method owner. Anything else carrying the legacy prefix — a field
	 * owner, a {@code new}, a class constant — would survive this pass and fail at link time far away from here,
	 * so {@link #stillNamesTheOldLoader} re-reads the finished bytes and says so out loud.
	 */
	static boolean adoptInteropHooksTheBaseStillNamesAfterTheOldLoader(ClassNode node) {
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				String adopted = ForbricMergedBaseCompatTransformer.LEGACY_INTEROP_OWNERS.get(call.owner);
				if (adopted == null) continue;
				call.owner = adopted;
				changed = true;
			}
		}
		if (changed) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] adopted old-loader interop hooks named by %s",
					node.name.replace('/', '.'));
		}
		return changed;
	}


	/** True if {@code classBytes} still mentions the old loader's package anywhere — a link error waiting to happen. */
	static boolean stillNamesTheOldLoader(byte[] classBytes) {
		byte[] needle = ForbricMergedBaseCompatTransformer.LEGACY_INTEROP_PACKAGE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		outer:
		for (int i = 0; i + needle.length <= classBytes.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (classBytes[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}
}
