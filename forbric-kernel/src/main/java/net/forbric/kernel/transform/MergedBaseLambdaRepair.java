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
 * Lambda bootstrap handles, and the two Bootstrap repairs that keep a nested boot from running twice.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseLambdaRepair {
	private MergedBaseLambdaRepair() {
	}


	static boolean repairLambdaBootstrapHandles(ClassNode node) {
		Map<String, MethodNode> methods = new HashMap<>();
		for (MethodNode method : node.methods) {
			methods.put(method.name + method.desc, method);
		}

		boolean changed = false;
		for (MethodNode caller : node.methods) {
			for (AbstractInsnNode insn = caller.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null) continue;
				for (int i = 0; i < indy.bsmArgs.length; i++) {
					if (!(indy.bsmArgs[i] instanceof Handle handle)) continue;
					Handle repaired = MergedBaseLambdaRepair.repairLambdaHandle(node, methods, caller, indy, handle);
					if (repaired == handle) continue;
					indy.bsmArgs[i] = repaired;
					changed = true;
				}
			}
		}
		return changed;
	}


	/**
	 * Gives merged {@code BlockState} its own {@code getAppearance}, because it inherits TWO.
	 *
	 * <p>The merged class declares {@code IBlockStateExtension} (NeoForge) and {@code IForgeBlockState}
	 * (MinecraftForge); fabric-api's mixin then adds {@code FabricBlockState}. NeoForge's and Fabric's both
	 * carry a {@code default getAppearance} with a byte-identical descriptor, neither overrides the other, and
	 * the class declares nothing — so the JVM refuses to choose and the FIRST caller dies:
	 * <pre>
	 * java.lang.IncompatibleClassChangeError: Conflicting default methods:
	 *   net/neoforged/neoforge/common/extensions/IBlockStateExtension.getAppearance
	 *   net/fabricmc/fabric/api/block/v1/FabricBlockState.getAppearance
	 *   at BlockState.getAppearance
	 *   at me.pepperbell.continuity.client.model.CtmBlockStateModel.emitQuads
	 * </pre>
	 * Measured on the reporting instance the moment connected textures were switched on — Continuity is a
	 * connected-texture mod, so asking a neighbour what it LOOKS like is the one thing it does, and nothing else
	 * in a 28-mod pack had ever called this method. On either loader alone only one default exists and the
	 * conflict cannot arise.
	 *
	 * <p>The body is written out rather than delegated to one side, because neither side is a choice: both
	 * defaults are {@code this.getBlock().getAppearance(this, level, pos, direction, queryState, queryPos)},
	 * differing only in how they obtain {@code this} (NeoForge through {@code self()}, Fabric through a
	 * {@code checkcast}). Writing it directly also means the resolver does not depend on which of the two
	 * interfaces is present at transform time — and fabric-api's is NOT, since a mixin adds it later.
	 */
	/**
	 * The same conflict one level down, on {@code Block} — and the level that actually crashed.
	 *
	 * <p>Giving {@code BlockState} its own {@code getAppearance} was correct and it works: it resolves and
	 * delegates to {@code getBlock().getAppearance(...)}. That delegate is where the SECOND copy of the same
	 * defect lives. {@code Block} declares {@code IBlockExtension} (NeoForge) and {@code IForgeBlock}
	 * (MinecraftForge); fabric-api's mixin adds {@code FabricBlock}; NeoForge's and Fabric's both default
	 * {@code getAppearance} with the same descriptor and {@code Block} declares neither, so every subclass that
	 * does not override it inherits two defaults:
	 * <pre>
	 * java.lang.IncompatibleClassChangeError: Conflicting default methods:
	 *   net/neoforged/neoforge/common/extensions/IBlockExtension.getAppearance
	 *   net/fabricmc/fabric/api/block/v1/FabricBlock.getAppearance
	 *   at MudBlock.getAppearance
	 *   at BlockState.getAppearance   &lt;- the first repair, working
	 * </pre>
	 * Measured on the reporting instance the day after the first half shipped. Fixing one frame of a crash and
	 * not asking whether the frame below it has the same shape is what made this two crashes instead of one.
	 *
	 * <p>The family is now closed rather than patched twice. Census of the two interface pairs on this carrier:
	 * {@code IBlockExtension} declares 64 defaults and {@code FabricBlock} 2; {@code IBlockStateExtension} 61
	 * and {@code FabricBlockState} 2; the ONLY name declared default by both sides, in either pair, is
	 * {@code getAppearance}. There is no third one waiting.
	 *
	 * <p>Both defaults here are literally {@code aload_1; areturn} — return the state you were asked about — so
	 * again there is no side to choose, and writing the body out keeps the resolver independent of which
	 * interface is present when the transformer runs.
	 */
	/**
	 * Restores {@code Bootstrap.bootStrap()}'s call to its own {@code wrapStreams()}, which routes
	 * {@code System.out}/{@code System.err} into log4j.
	 *
	 * <p>Vanilla calls it as the last thing bootstrap does. NeoForge's patch spends that exact slot on
	 * {@code GameData.vanillaSnapshot()} instead, and the byte merge kept NeoForge's half — so the merged
	 * {@code bootStrap()} runs the snapshot and never wraps the streams. Measured: stock 26.2 has
	 * {@code invokestatic wrapStreams:()V} at bci 81; in the merged base the ONLY class mentioning
	 * {@code wrapStreams} is {@code Bootstrap} itself, and inside it the only mention is the declaration.
	 * The method's body survived the merge intact — it still builds {@code LoggedPrintStream("STDOUT")} and
	 * calls {@code System.setOut} — so nothing needs writing, only calling.
	 *
	 * <p>What it costs while dead: every line a mod PRINTS instead of logging is gone. Not degraded, not
	 * misfiled — absent. MouseTweaks writes its entire diagnostic output through {@code System.out}, so a
	 * player told to turn on its debug mode produces a log with nothing in it, and the silence reads as
	 * "the mod said nothing" rather than "nobody was listening". Any mod printing a stack trace to stderr
	 * disappears the same way.
	 *
	 * <p>Both halves are kept. The snapshot is NeoForge's and it stays exactly where NeoForge put it; the
	 * wrap goes after it, at vanilla's position relative to {@code bootstrapDuration}. Restoring one
	 * ecosystem's line must not cost the other's — that is the merge failure this repair is undoing, and
	 * doing it in reverse would be no better.
	 */
	static boolean wrapTheStreamsVanillaWraps(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.BOOTSTRAP.equals(node.name) || node.methods == null) return false;
		if (!MergedBaseAsm.hasMethod(node, "wrapStreams", "()V")) return false;

		MethodNode bootStrap = null;
		for (MethodNode method : node.methods) {
			if ("bootStrap".equals(method.name) && "()V".equals(method.desc)) bootStrap = method;
		}
		if (bootStrap == null || bootStrap.instructions == null) return false;

		// Already calling it (a future base that keeps vanilla's line) — this repair is then a no-op, and must
		// report itself as one rather than inserting a second wrap that would nest the streams twice.
		for (AbstractInsnNode insn : bootStrap.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& ForbricMergedBaseCompatTransformer.BOOTSTRAP.equals(call.owner) && "wrapStreams".equals(call.name)) {
				return false;
			}
		}

		// Vanilla's position: immediately before bootstrapDuration is written, which is the last thing the
		// method does. Anchoring on that field write rather than on the preceding call keeps the insertion
		// correct whichever ecosystem's calls precede it.
		AbstractInsnNode anchor = null;
		for (AbstractInsnNode insn : bootStrap.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
					&& ForbricMergedBaseCompatTransformer.BOOTSTRAP.equals(field.owner) && "bootstrapDuration".equals(field.name)) {
				anchor = insn;
				break;
			}
		}
		if (anchor == null) return false;

		bootStrap.instructions.insertBefore(anchor,
				new MethodInsnNode(Opcodes.INVOKESTATIC, ForbricMergedBaseCompatTransformer.BOOTSTRAP, "wrapStreams", "()V", false));
		bootStrap.maxStack = Math.max(bootStrap.maxStack, 2);

		ForbricLog.warn("[Forbric/MergedBaseCompat] Bootstrap now wraps System.out/System.err into log4j again "
				+ "— NeoForge's patch spends vanilla's wrapStreams() slot on GameData.vanillaSnapshot() and the "
				+ "merge kept only that half, so every line a mod PRINTED rather than logged was absent from the "
				+ "log entirely (a mod's own debug mode produced a log with nothing in it). Both calls now run");
		return true;
	}


	/**
	 * Gives {@code Bootstrap.bootStrap()}'s already-bootstrapped path its own {@code return}, ahead of the body, so
	 * the method's last {@code return} — the one a mixin's {@code @At("TAIL")} names — is reached only by the call
	 * that actually bootstrapped.
	 *
	 * <p>Vanilla's shape is {@code if (!isBootstrapped) { isBootstrapped = true; ... } return;}: one return, reached
	 * by every call. On vanilla and on Fabric that is one call per process ({@code Main.main} / the client's
	 * {@code Main}), so a mod injecting at TAIL runs once, after bootstrap. The merged game also carries
	 * MinecraftForge's {@code ForgeRegistries.<clinit>}, whose {@code init()} calls {@code Bootstrap.bootStrap()}
	 * to make sure bootstrap has happened — and it is first touched from INSIDE bootstrap, while {@code Items}
	 * constructs a bucket. {@code isBootstrapped} is already true there, the nested call skips the body and falls
	 * through to the same return, and every TAIL handler runs half-way through bootstrap and then again at its
	 * end. Measured with {@code -Xlog:class+init}: {@code ForgeRegistries} initialises between {@code BucketItem}
	 * and cristellib's {@code CristelLib}; cristellib's TAIL handler freezes its pack registry and config data the
	 * first time and throws "Cannot set Auto Config data twice" the second, and the server does not start.
	 *
	 * <p>Only TAIL changes. The early return is still a return, so an {@code @At("RETURN")} handler still sees
	 * every call; HEAD is untouched; the body and the call that runs it are exactly what they were.
	 */
	static boolean returnFromANestedBootstrapBeforeItsTail(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.BOOTSTRAP.equals(node.name) || node.methods == null) return false;
		MethodNode bootStrap = null;
		for (MethodNode method : node.methods) {
			if ("bootStrap".equals(method.name) && "()V".equals(method.desc)) bootStrap = method;
		}
		if (bootStrap == null || bootStrap.instructions == null) return false;

		// The guard: the first real instructions are GETSTATIC isBootstrapped; IFNE skip.
		AbstractInsnNode first = MergedBaseAsm.realAfter(bootStrap.instructions.getFirst(), true);
		if (!(first instanceof FieldInsnNode read) || read.getOpcode() != Opcodes.GETSTATIC
				|| !ForbricMergedBaseCompatTransformer.BOOTSTRAP.equals(read.owner) || !"isBootstrapped".equals(read.name) || !"Z".equals(read.desc)) {
			return false;
		}
		AbstractInsnNode next = MergedBaseAsm.realAfter(first.getNext(), true);
		// Already repaired (IFEQ body; RETURN) or a base that returns early itself: nothing to do.
		if (!(next instanceof JumpInsnNode guard) || guard.getOpcode() != Opcodes.IFNE) return false;

		// The guard must skip to the method's LAST return, or this is not the shape the repair is about.
		AbstractInsnNode skipped = MergedBaseAsm.realAfter(guard.label, true);
		AbstractInsnNode lastReturn = null;
		for (AbstractInsnNode insn = bootStrap.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
			if (insn.getOpcode() == Opcodes.RETURN) { lastReturn = insn; break; }
		}
		if (skipped == null || skipped != lastReturn) return false;

		LabelNode body = new LabelNode();
		InsnList early = new InsnList();
		early.add(new InsnNode(Opcodes.RETURN));
		early.add(body);
		// Method entry's frame: a static no-argument method, nothing on the stack.
		early.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		bootStrap.instructions.insert(guard, early);
		guard.setOpcode(Opcodes.IFEQ);
		guard.label = body;

		ForbricLog.info("[Forbric/MergedBaseCompat] Bootstrap.bootStrap() returns before its TAIL when bootstrap has "
				+ "already begun — MinecraftForge's ForgeRegistries calls it again from inside the first call, which ran "
				+ "every TAIL handler twice, the first time half-way through bootstrap");
		return true;
	}


	static Handle repairLambdaHandle(ClassNode owner, Map<String, MethodNode> methods, MethodNode caller,
			InvokeDynamicInsnNode indy, Handle handle) {
		if (!owner.name.equals(handle.getOwner()) || !handle.getName().startsWith("lambda$")) return handle;

		MethodNode target = methods.get(handle.getName() + handle.getDesc());
		if (target == null) return handle;

		boolean methodStatic = (target.access & Opcodes.ACC_STATIC) != 0;
		boolean handleStatic = handle.getTag() == Opcodes.H_INVOKESTATIC;
		if (methodStatic == handleStatic) return handle;

		if (!methodStatic && handleStatic) {
			if (!MergedBaseLambdaRepair.capturesOwner(owner, indy.desc)) {
				if ((caller.access & Opcodes.ACC_STATIC) != 0 || !MergedBaseLambdaRepair.prependThisCapture(owner, caller, indy)) {
					return handle;
				}
			}
			ForbricLog.warn("[Forbric/MergedBaseCompat] repaired lambda bootstrap handle %s.%s%s "
					+ "from static to instance; invokedynamic is now %s",
					owner.name.replace('/', '.'), handle.getName(), handle.getDesc(), indy.desc);
			return new Handle(Opcodes.H_INVOKEVIRTUAL, handle.getOwner(), handle.getName(), handle.getDesc(), false);
		}

		ForbricLog.warn("[Forbric/MergedBaseCompat] repaired lambda bootstrap handle %s.%s%s from tag %d to %d",
				owner.name.replace('/', '.'), handle.getName(), handle.getDesc(), handle.getTag(), Opcodes.H_INVOKESTATIC);
		return new Handle(Opcodes.H_INVOKESTATIC, handle.getOwner(), handle.getName(), handle.getDesc(), false);
	}


	static boolean capturesOwner(ClassNode owner, String invokedynamicDesc) {
		Type[] args = Type.getArgumentTypes(invokedynamicDesc);
		return args.length > 0 && args[0].getSort() == Type.OBJECT && owner.name.equals(args[0].getInternalName());
	}


	static boolean prependThisCapture(ClassNode owner, MethodNode caller, InvokeDynamicInsnNode indy) {
		AbstractInsnNode insertionPoint = MergedBaseLambdaRepair.capturedArgsStart(indy);
		if (insertionPoint == null) return false;
		caller.instructions.insertBefore(insertionPoint, new VarInsnNode(Opcodes.ALOAD, 0));
		indy.desc = MergedBaseLambdaRepair.prependArgument(Type.getObjectType(owner.name), indy.desc);
		caller.maxStack = Math.max(caller.maxStack, caller.maxStack + 1);
		return true;
	}


	static AbstractInsnNode capturedArgsStart(InvokeDynamicInsnNode indy) {
		Type[] args = Type.getArgumentTypes(indy.desc);
		if (args.length == 0) return indy;

		AbstractInsnNode cursor = indy.getPrevious();
		AbstractInsnNode first = null;
		for (int i = args.length - 1; i >= 0; i--) {
			cursor = MergedBaseAsm.previousReal(cursor);
			if (!MergedBaseLambdaRepair.isLocalLoadFor(args[i], cursor)) return null;
			first = cursor;
			cursor = cursor.getPrevious();
		}
		return first;
	}


	static boolean isLocalLoadFor(Type type, AbstractInsnNode insn) {
		return insn instanceof VarInsnNode var && var.getOpcode() == MergedBaseLambdaRepair.loadOpcode(type);
	}


	static int loadOpcode(Type type) {
		return switch (type.getSort()) {
			case Type.LONG -> Opcodes.LLOAD;
			case Type.FLOAT -> Opcodes.FLOAD;
			case Type.DOUBLE -> Opcodes.DLOAD;
			case Type.ARRAY, Type.OBJECT -> Opcodes.ALOAD;
			default -> Opcodes.ILOAD;
		};
	}


	static String prependArgument(Type argument, String methodDesc) {
		Type[] oldArgs = Type.getArgumentTypes(methodDesc);
		Type[] newArgs = new Type[oldArgs.length + 1];
		newArgs[0] = argument;
		System.arraycopy(oldArgs, 0, newArgs, 1, oldArgs.length);
		return Type.getMethodDescriptor(Type.getReturnType(methodDesc), newArgs);
	}
}
