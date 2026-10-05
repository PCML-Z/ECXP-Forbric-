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
 * The protocol field MinecraftForge's outbound channels read.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseNetworkRepair {
	private MergedBaseNetworkRepair() {
	}


	/**
	 * MinecraftForge's network channels pick the vanilla packet type for an outgoing payload from
	 * {@code Connection.getProtocol()}, which reads a Forge-added {@code outboundProtocol} field. Forge's patch keeps
	 * that field current from inside {@code setupOutboundProtocol} — a lambda chained onto the pipeline task — and
	 * NeoForge won the merge of that method, so the lambda survives in the class and nothing calls it. The
	 * constructor still seeds the field with the handshake protocol on the CLIENT flow (the server flow leaves it
	 * null, and {@code getProtocol} then falls back to the inbound field, which the merged
	 * {@code setupInboundProtocol} does maintain — so the server side never showed this). A Forbric client thus
	 * reports HANDSHAKING for the life of the connection and every Forge channel send from the client throws
	 * "Unsupported protocol HANDSHAKING in Forge Networking Channel" — its own channel declaration
	 * ({@code ChannelListManager.addChannels}) first of all, so the server's {@code Channel.isRemotePresent} never
	 * saw the client's channels.
	 *
	 * <p>Store the new protocol at the head of {@code setupOutboundProtocol}. Synchronous rather than
	 * pipeline-ordered, which for this field's one reader is the better contract: a payload built after the switch
	 * must already be a packet of the new protocol, because the pipeline task is queued ahead of it.
	 */
	static boolean keepForgeOutboundProtocolCurrent(ClassNode node) {
		if (!"net/minecraft/network/Connection".equals(node.name)) return false;
		String protocolInfo = "Lnet/minecraft/network/ProtocolInfo;";
		if (!MergedBaseAsm.hasField(node, "outboundProtocol", protocolInfo)) return false;
		MethodNode setup = MergedBaseAsm.findMethod(node, "setupOutboundProtocol", "(" + protocolInfo + ")V");
		if (setup == null) return false;

		// Coherent already (a single-ecosystem base, or a merge that kept Forge's body): the method stores the field
		// itself, or still chains the lambda that does.
		if (MergedBaseAsm.writesField(setup, "outboundProtocol")) return false;
		for (AbstractInsnNode insn = setup.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
			for (Object arg : indy.bsmArgs) {
				if (arg instanceof Handle handle && node.name.equals(handle.getOwner())) {
					MethodNode lambda = MergedBaseAsm.findMethod(node, handle.getName(), handle.getDesc());
					if (lambda != null && MergedBaseAsm.writesField(lambda, "outboundProtocol")) return false;
				}
			}
		}

		InsnList store = new InsnList();
		store.add(new VarInsnNode(Opcodes.ALOAD, 0));
		store.add(new VarInsnNode(Opcodes.ALOAD, 1));
		store.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "outboundProtocol", protocolInfo));
		setup.instructions.insert(store);
		setup.maxStack = Math.max(setup.maxStack, 2);
		ForbricLog.warn("[Forbric/MergedBaseCompat] Connection.setupOutboundProtocol now updates MinecraftForge's "
				+ "outboundProtocol — the merge dropped the lambda that did, so a client Connection reported HANDSHAKING "
				+ "forever and every Forge channel send from the client threw");
		return true;
	}
}
