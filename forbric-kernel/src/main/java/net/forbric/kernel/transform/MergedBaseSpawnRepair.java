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
 * Spawn reasons, spawner mobs, pack finders, and the save on the teardown path.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseSpawnRepair {
	private MergedBaseSpawnRepair() {
	}


	/**
	 * Points {@code Mob.getSpawnReason()} at the spawn field the game actually writes.
	 *
	 * <p>The merge left {@code Mob} carrying both families' spawn fields under different names —
	 * {@code spawnType} and {@code spawnReason} — and every producer writes {@code spawnType}. {@code javap} on
	 * the merged {@code Mob}: two {@code putfield spawnType}, zero {@code putfield spawnReason}. So
	 * {@code getSpawnReason()} returned null for every mob that has ever existed.
	 *
	 * <p>What that costs is a whole category of mod behaviour rather than a crash: "was this mob spawned
	 * naturally, from a spawner, by a spawn egg, or by a command" is how mob-drop, anti-farm, difficulty and
	 * quest mods decide whether to act at all, and a null sends every one of them down the same branch — usually
	 * the one that does nothing, silently.
	 *
	 * <p>Only the read moves. The field declaration stays, because an access widener or a mixin may name it, and
	 * removing it would cost more than the dead field does.
	 */
	static boolean readTheSpawnReasonThatIsActuallyWritten(ClassNode node) {
		if (!"net/minecraft/world/entity/Mob".equals(node.name)) return false;
		if (!MergedBaseAsm.hasField(node, "spawnReason", ForbricMergedBaseCompatTransformer.SPAWN_REASON) || !MergedBaseAsm.hasField(node, "spawnType", ForbricMergedBaseCompatTransformer.SPAWN_REASON)) return false;

		// If anything ever writes spawnReason, the field is live and must be left alone — the same guard the
		// particle-map reroute uses, and for the same reason: a future base may keep the other family's producer.
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
						&& node.name.equals(field.owner) && "spawnReason".equals(field.name)) {
					return false;
				}
			}
		}

		boolean changed = false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
						&& node.name.equals(field.owner) && "spawnReason".equals(field.name)
						&& ForbricMergedBaseCompatTransformer.SPAWN_REASON.equals(field.desc)) {
					field.name = "spawnType";
					changed = true;
				}
			}
		}
		if (changed) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] Mob.getSpawnReason() read a field nothing ever writes, so "
					+ "it answered null for every mob — mods that branch on how a mob was spawned (spawner, egg, "
					+ "command, natural) all took the same branch. It now reads the field the game writes");
		}
		return changed;
	}


	/**
	 * Stops a throw in {@code IntegratedServer.teardownPublishedState} from costing the world save.
	 *
	 * <p>{@code IntegratedServer.stopServer()} is two calls and a return:
	 *
	 * <pre>
	 *   0: aload_0; invokevirtual teardownPublishedState:()V
	 *   4: aload_0; invokespecial MinecraftServer.stopServer:()V
	 *   8: return
	 * </pre>
	 *
	 * <p>with no exception table. Everything durable happens in the SECOND call — {@code MinecraftServer
	 * .stopServer} is where "Saving players", {@code PlayerList.saveAll}, "Saving worlds" and the chunk flush
	 * live — so anything the first call throws takes the entire save with it. {@code MinecraftServer.runServer}
	 * catches it one frame up and logs "Exception stopping the server", which reads like a tidy-up problem.
	 *
	 * <p>It is not hypothetical. Measured on a real install: an Alt+F4 with a chat glyph still unbaked ran
	 * {@code teardownPublishedState -> updateCommandsAllowedForOtherPlayers -> LocalPlayer.refreshChatAbilities},
	 * which re-splits the chat log, which bakes a glyph, which asserts the render thread — on the server thread.
	 * The region files still reached disk because the chunk storage closes itself, but {@code level.dat} was an
	 * autosave old, so the player's position, inventory and the world clock were sixty seconds behind.
	 *
	 * <p>Not the kernel's damage: this ordering is byte-identical in the untouched MinecraftForge base and the
	 * untouched NeoForge base, so it is upstream shape. It is repaired here anyway because this is a base the
	 * kernel owns and the cost is a player's data.
	 *
	 * <p>The wrap is deliberately narrow — the try covers the teardown call and nothing else — and the order is
	 * left exactly as upstream wrote it. Reordering the two calls would also have saved first, but it would have
	 * moved when the LAN pinger stops and when the multiplayer scope flips, which is a behaviour change to buy
	 * something a two-instruction exception range already buys.
	 */
	static boolean keepTheSaveOffTheTeardownsFailurePath(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.INTEGRATED_SERVER.equals(node.name)) return false;
		MethodNode stop = MergedBaseAsm.findMethod(node, "stopServer", "()V");
		if (stop == null || stop.instructions == null || stop.instructions.size() == 0) return false;
		// An exception table here means a previous pass already wrapped it, or the shape is not the one read
		// above. Either way this pass has nothing it can safely say about the body.
		if (stop.tryCatchBlocks != null && !stop.tryCatchBlocks.isEmpty()) return false;

		MethodInsnNode teardown = null;
		for (AbstractInsnNode insn : stop.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
					&& ForbricMergedBaseCompatTransformer.TEARDOWN_PUBLISHED_STATE.equals(call.name) && "()V".equals(call.desc)) {
				teardown = call;
				break;
			}
		}
		if (teardown == null) return false;
		// The receiver push has to be inside the protected range too, or the handler would be entered with a
		// half-built stack. Only the `aload_0; invokevirtual` pair is a shape this pass understands.
		AbstractInsnNode receiver = MergedBaseAsm.previousReal(teardown.getPrevious());
		if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
			return false;
		}
		// And the save must actually be downstream of it — wrapping a teardown that nothing follows would cost
		// the report without buying the save.
		if (!MergedBaseSpawnRepair.callsSuperStopServer(teardown)) return false;

		LabelNode start = new LabelNode();
		LabelNode end = new LabelNode();
		LabelNode handler = new LabelNode();
		LabelNode after = new LabelNode();
		stop.instructions.insertBefore(receiver, start);

		InsnList tail = new InsnList();
		tail.add(end);
		tail.add(new JumpInsnNode(Opcodes.GOTO, after));
		tail.add(handler);
		tail.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] { node.name }, 1,
				new Object[] { "java/lang/Throwable" }));
		tail.add(new LdcInsnNode("[Forbric/Shutdown] the integrated server's published-state teardown threw on the "
				+ "way out - saving the world anyway. Upstream runs that teardown BEFORE MinecraftServer"
				+ ".stopServer, which is where players and worlds are written, so this used to end the process "
				+ "with level.dat still at the last autosave"));
		tail.add(new InsnNode(Opcodes.SWAP));
		tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.FORBRIC_LOG, "warn",
				"(Ljava/lang/String;Ljava/lang/Throwable;)V", false));
		tail.add(after);
		tail.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		stop.instructions.insert(teardown, tail);

		stop.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
		stop.maxStack = Math.max(stop.maxStack, 2);
		ForbricLog.info("[Forbric/MergedBaseCompat] IntegratedServer.stopServer now saves even if the published-state "
				+ "teardown throws — upstream runs the teardown first and unguarded, so one throw on the way out "
				+ "skipped the player and world save entirely");
		return true;
	}


	/** Whether the super call that performs the save still follows the teardown in this body. */
	static boolean callsSuperStopServer(MethodInsnNode teardown) {
		for (AbstractInsnNode insn = teardown.getNext(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
					&& "stopServer".equals(call.name) && "()V".equals(call.desc)) {
				return true;
			}
		}
		return false;
	}


	/**
	 * Routes the spawner call to the kernel before SpawnerFinalizeInjector adds its proven ValueInput.
	 *
	 * <p>Both ecosystems patched {@code BaseSpawner.serverTick}, NeoForge's body won, and
	 * {@code onFinalizeSpawnSpawner} is therefore called from nowhere — while {@code collective}, in the test
	 * pack, subscribes to the event it posts. Putting MinecraftForge's own instruction run back would mean
	 * splicing it into a body with NeoForge's local numbering, which is the three-way merge this tree does not
	 * have. This first exchange preserves the descriptor. The following injector adds the actual ValueInput
	 * from the entity-loading data flow; without it, the legacy entry reports the missing input rather than
	 * inventing a null Forge argument or pretending an already-finalized mob can be changed retroactively.
	 *
	 * <p>A method that already calls MinecraftForge's own finalize hook is left alone: the kernel entry would post
	 * that event a second time. SpawnerFinalizeInjector reports such a caller.
	 */
	static boolean letMinecraftForgeSeeSpawnerMobs(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.BASE_SPAWNER.equals(node.name)) return false;
		int redirected = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null || SpawnerFinalizeInjector.carriesForgeFinalize(method)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !ForbricMergedBaseCompatTransformer.NEO_EVENT_HOOKS.equals(call.owner)
						|| !"finalizeMobSpawnSpawner".equals(call.name)
						|| !SpawnerFinalizeInjector.OLD_DESC.equals(call.desc)) {
					continue;
				}
				call.owner = ForbricMergedBaseCompatTransformer.KERNEL_SPAWNER_FINALIZE;
				redirected++;
			}
		}
		if (redirected == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] BaseSpawner finalization routed through the kernel "
				+ "(%d call site(s)); the input injector supplies the Forge event's actual ValueInput", redirected);
		return true;
	}


	/**
	 * Sends every pack-repository population through the kernel, so MinecraftForge is asked for finders too.
	 *
	 * <p>Four call sites, in two client screens and two {@code ServerPacksSource} factories, and no single class
	 * to anchor on — hence a scanned claim rather than a fixed one. MinecraftForge's own call site is gone from
	 * all of them and NeoForge's survived, so a Forge-family mod contributing a data pack is never asked.
	 */
	static boolean letMinecraftForgeAddPackFinders(ClassNode node) {
		// Not the redirect TARGET itself. Its whole body is a call to the method being redirected, so rewriting
		// that call points it at itself: the first pack repository built recurses until the stack ends, and the
		// server never reaches Done. A scanned repair with no fixed anchor has to say what it is not allowed to
		// touch, because nothing else will.
		if (ForbricMergedBaseCompatTransformer.KERNEL_PACK_FINDERS.equals(node.name)) return false;
		int redirected = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !ForbricMergedBaseCompatTransformer.NEO_RESOURCE_PACK_LOADER.equals(call.owner)
						|| !"populatePackRepository".equals(call.name)) {
					continue;
				}
				call.owner = ForbricMergedBaseCompatTransformer.KERNEL_PACK_FINDERS;
				redirected++;
			}
		}
		if (redirected == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] %s now populates its pack repository through the kernel "
				+ "(%d call site(s)) — the merge kept only NeoForge's, so MinecraftForge mods were never asked "
				+ "for pack finders", node.name, redirected);
		return true;
	}
}
