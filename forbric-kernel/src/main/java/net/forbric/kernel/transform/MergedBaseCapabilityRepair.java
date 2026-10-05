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
 * Capability lifecycle stubs, unassigned loggers, and merge stubs that hide a real superclass method.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseCapabilityRepair {
	private MergedBaseCapabilityRepair() {
	}


	/**
	 * Removes a method whose whole body delegates to an interface default, when a SUPERCLASS has a real one.
	 *
	 * <h2>What the merge does</h2>
	 *
	 * <p>It injects these delegates blindly. Measured across the whole merged base against both unmerged bases:
	 * 256 methods are pure {@code Iface.super.<same method>} delegates that shadow a superclass with a real
	 * implementation, and 253 of them exist in neither unmerged base — so they are the merge's doing. The three
	 * that are not are vanilla's own and are left alone by the rule below, because their superclass merely
	 * delegates the same way.
	 *
	 * <p>What that costs depends on the method. {@code VehicleEntity.getDisplayName()} shadows
	 * {@code Entity.getDisplayName()}, which is the method that applies team colours and prefixes — so a boat or
	 * minecart loses its team formatting in every name it is shown under.
	 *
	 * <h2>Why THIS rule and not the older one</h2>
	 *
	 * <p>{@link #dropInterfaceDefaultShadowingOverrides} does the same thing for a hand-kept allowlist, and that
	 * allowlist exists because a wider version once crashed every GUI screen at the title with
	 * {@code IncompatibleClassChangeError: Conflicting default methods}: {@code getRectangle} is supplied as a
	 * default by two unrelated interfaces, so removing the override left two competing candidates.
	 *
	 * <p>The condition here cannot hit that. A concrete superclass method always wins over any interface default,
	 * so when the chain HAS one there is nothing for defaults to compete over — the crash happened precisely in
	 * classes whose chain had none. That makes this rule both wider and safer than the list it complements, and
	 * it needs no list to maintain.
	 *
	 * <h2>Why it is still limited to one method</h2>
	 *
	 * <p>Because VANILLA writes this shape on purpose too. {@code AbstractContainerWidget.nextFocusPath} is a
	 * pure delegate over a superclass with a real implementation, and it is present in BOTH unmerged bases — so
	 * "delegate over a real superclass method" alone does not mean "merge damage", and a rule keyed on the shape
	 * would quietly change vanilla's own behaviour. A first version of this rule did exactly that, and the test
	 * beside it caught it.
	 *
	 * <p>So the shape is necessary but not sufficient, and the set is measured rather than guessed: every such
	 * method in the merged base was differenced against both unmerged bases, and
	 * {@code getDisplayName()Lnet/minecraft/network/chat/Component;} is the entry whose 20 occurrences are all
	 * merge-introduced AND whose bypassed implementation does something a player can see. Widening it means
	 * repeating that measurement, not adding a name.
	 *
	 * <p>Stands down entirely without a class resolver: it cannot answer its own question without reading the
	 * superclass chain, and guessing is what the allowlist exists to avoid.
	 */
	static boolean dropStubsThatBypassARealSuperclassMethod(ClassNode node, java.util.function.Function<String, byte[]> classBytes) {
		if (classBytes == null || node.superName == null || node.methods == null) return false;

		List<MethodNode> shadowing = new java.util.ArrayList<>();
		for (MethodNode method : node.methods) {
			if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) continue;
			if (!ForbricMergedBaseCompatTransformer.MEASURED_MERGE_STUBS.contains(method.name + method.desc)) continue;
			if (!MergedBaseCapabilityRepair.isPureInterfaceDelegate(method)) continue;
			if (!MergedBaseCapabilityRepair.superclassHasARealImplementation(classBytes, node.superName, method.name, method.desc)) continue;
			shadowing.add(method);
		}
		if (shadowing.isEmpty()) return false;

		node.methods.removeAll(shadowing);
		for (MethodNode dropped : shadowing) {
			ForbricLog.debug("[Forbric/MergedBaseCompat] dropped %s.%s%s — its whole body handed off to an "
					+ "interface default while its superclass has a real implementation",
					node.name.replace('/', '.'), dropped.name, dropped.desc);
		}
		return true;
	}


	/** Whether {@code method}'s entire body is {@code SomeInterface.super.<this very method>(args…)}. */
	static boolean isPureInterfaceDelegate(MethodNode method) {
		if (method.instructions == null) return false;

		List<AbstractInsnNode> body = new java.util.ArrayList<>();
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
		return call.name.equals(method.name) && call.desc.equals(method.desc);
	}


	/**
	 * Whether the superclass chain declares this method with a body that is NOT itself such a delegate.
	 *
	 * <p>A superclass that delegates the same way is not something to be shadowed — removing the subclass's copy
	 * would change nothing — and those are exactly the three cases that exist in the unmerged bases too.
	 *
	 * <p>A class the resolver cannot produce ends the walk with "no": the honest answer when the chain cannot be
	 * read is that nothing is known to be shadowed, and the method stays.
	 */
	static boolean superclassHasARealImplementation(java.util.function.Function<String, byte[]> classBytes, String superName, String name, String desc) {
		for (String at = superName; at != null; ) {
			byte[] bytes = classBytes.apply(at.replace('.', '/') + ".class");
			if (bytes == null) return false;

			ClassNode parent = new ClassNode();
			try {
				new ClassReader(bytes).accept(parent, ClassReader.SKIP_FRAMES);
			} catch (RuntimeException unreadable) {
				return false;
			}

			for (MethodNode m : parent.methods) {
				if (!m.name.equals(name) || !m.desc.equals(desc)) continue;
				if ((m.access & Opcodes.ACC_ABSTRACT) != 0) return false;
				return !MergedBaseCapabilityRepair.isPureInterfaceDelegate(m);
			}
			at = parent.superName;
		}
		return false;
	}


	/**
	 * Gives the three root game types the capability lifecycle methods their own merged code calls.
	 *
	 * <p>The merge put each root class under NeoForge's {@code AttachmentHolder}, which dropped MinecraftForge's
	 * capability superclass and the two lifecycle methods that came with it — while keeping MinecraftForge's
	 * method BODIES further down. {@code javap} on the merged {@code BlockEntity}: {@code onChunkUnloaded()} is
	 * MinecraftForge's body and its one instruction is {@code invokevirtual BlockEntity.invalidateCaps}, a method
	 * that resolves nowhere. Walking {@code BlockEntity} to {@code Object} finds no declaration, and the one
	 * interface that could supply a default declares only {@code onChunkUnloaded} itself.
	 *
	 * <p>Nothing in the merged base calls that today — NeoForge's half removed the call site — so this is not a
	 * live crash. It is a live TRAP: a MinecraftForge mod's block entity that overrides {@code invalidateCaps} and
	 * calls {@code super}, which is ordinary in storage and machinery mods, links against a method that is not
	 * there and dies at that call with a message naming neither the merge nor the kernel.
	 *
	 * <p>No-ops, deliberately, and this is NOT a capability system. There is nothing here to invalidate or revive:
	 * the merged classes carry no MinecraftForge capability provider. A no-op makes the call link and do the
	 * nothing that is already happening. Actually attaching capabilities means giving these classes a provider,
	 * which is a merge-tool change, not a transformer one.
	 */
	static boolean addTheMissingCapabilityLifecycleStubs(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.CAPABILITY_ROOTS.contains(node.name)) return false;

		boolean changed = false;
		for (String name : new String[] {"invalidateCaps", "reviveCaps"}) {
			if (MergedBaseAsm.findMethod(node, name, "()V") != null) continue;

			MethodNode stub = new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null);
			stub.instructions.add(new InsnNode(Opcodes.RETURN));
			stub.maxStack = 0;
			stub.maxLocals = 1;
			node.methods.add(stub);
			changed = true;
		}

		if (changed) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] %s had no capability lifecycle methods while its own merged "
					+ "code still calls them — a MinecraftForge mod overriding one and calling super would have "
					+ "died on a method that resolves nowhere. They now exist and do nothing, which is what is "
					+ "already happening: these classes carry no capability provider.", node.name.replace('/', '.'));
		}
		return changed;
	}


	/**
	 * Fills in a {@code static final Logger} that survived the merge with nothing left to assign it.
	 *
	 * <p>When both families patch the same class, one family's {@code <clinit>} wins whole and the loser's
	 * assignments go with it — including assignments to fields the loser ADDED, which are kept as declarations.
	 * Such a field is then null forever, and there is no diagnostic: the class links, loads and works until
	 * something reads it.
	 *
	 * <p>A scan of the whole merged base finds exactly one logger in this state,
	 * {@code ResourceManagerRegistryLoadTask.LOGGER}, and it is read from the branch that handles a datapack
	 * entry a condition has switched OFF — which is what a Forge-family datapack does whenever it guards content
	 * on another mod being installed. So the branch meant to say "skipping this entry" threw instead, and the
	 * world would not open.
	 *
	 * <p>Only loggers, and only unwritten ones. A logger has one obvious correct value and building it needs
	 * nothing from the class; the other seven unwritten statics in this base carry codecs and callbacks that
	 * cannot be invented here and need the merge itself to stop dropping them.
	 */
	static boolean giveTheUnwrittenLoggerAValue(ClassNode node) {
		boolean changed = false;
		for (FieldNode field : node.fields) {
			if ((field.access & Opcodes.ACC_STATIC) == 0) continue;
			if (!ForbricMergedBaseCompatTransformer.LOGGER_DESC.equals(field.desc)) continue;
			if (MergedBaseAsm.writesStatic(node, field.name)) continue;

			MethodNode clinit = MergedBaseAsm.findMethod(node, "<clinit>", "()V");
			if (clinit == null) {
				clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
				clinit.instructions.add(new InsnNode(Opcodes.RETURN));
				node.methods.add(clinit);
			}

			// At the TOP of <clinit>, not before the RETURN: anything else the initialiser does may log, and a
			// repair that lands last would leave exactly the window this is closing.
			InsnList assign = new InsnList();
			assign.add(new LdcInsnNode(Type.getObjectType(node.name)));
			assign.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/slf4j/LoggerFactory", "getLogger",
					"(Ljava/lang/Class;)Lorg/slf4j/Logger;", false));
			assign.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, field.name, ForbricMergedBaseCompatTransformer.LOGGER_DESC));
			clinit.instructions.insert(assign);
			clinit.maxStack = Math.max(clinit.maxStack, 1);

			ForbricLog.warn("[Forbric/MergedBaseCompat] %s.%s is a logger the merge left with no assignment, so it "
					+ "was null forever and whichever branch reads it threw instead of logging. It is now "
					+ "initialised.", node.name.replace('/', '.'), field.name);
			changed = true;
		}
		return changed;
	}
}
