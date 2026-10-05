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
 * Worldgen bytes the merge rounded, dropped, or saved differently from vanilla.
 *
 * <p>Called by {@link ForbricMergedBaseCompatTransformer}. The bytes written here are the
 * repairs that used to live in that class; only the file changed.
 */
final class MergedBaseWorldRepair {
	private MergedBaseWorldRepair() {
	}


	/**
	 * Gives {@code ChunkGenerator.featuresPerStep} vanilla's descriptor back, and routes the one use that needed
	 * MinecraftForge's through a guard.
	 *
	 * <h2>Why this one is not the ParticleResources shape</h2>
	 *
	 * <p>{@link #giveTheVanillaParticleMapAViewOfTheLiveOne} repairs a field the merge kept TWICE, one of them
	 * unwritten. This is the other half of that family, and the worse half: MinecraftForge RE-TYPES the vanilla
	 * field — {@code Supplier<List<StepFeatureData>>} becomes its own {@code ClearableLazy<...>}, so that
	 * {@code refreshFeaturesPerStep()} has something to invalidate — and the merge keeps only MinecraftForge's
	 * declaration. Vanilla's descriptor does not exist at all, so there is no unwritten field to give a view to.
	 *
	 * <p>A whole-artifact census of the merged base against stock 26.2 finds six vanilla fields in this state;
	 * this is the one that costs a boot. fabric-api's {@code fabric-biome-api-v1} does not use an {@code @Accessor}
	 * — {@code BiomeModificationImpl.lambda$finalizeWorldGen$1} is a plain access-widened
	 * {@code putfield ChunkGenerator.featuresPerStep : Ljava/util/function/Supplier;} — so it gets
	 * {@code NoSuchFieldError} and the DEDICATED SERVER DOES NOT START the moment any Fabric biome modification
	 * applies. Installing balm, a library a large part of the Fabric ecosystem depends on, is enough to trigger it.
	 * lithostitched's {@code @Accessor setFeaturesPerStep(Supplier)} fails to bind for the same reason, from the
	 * other ecosystem.
	 *
	 * <h2>Why the repair is to move the field back rather than to add a second one</h2>
	 *
	 * <p>Because both descriptors can be satisfied by ONE field: {@code ClearableLazy extends Lazy extends
	 * Supplier}, so the value MinecraftForge's constructor already stores IS a {@code Supplier}. Declaring the
	 * field with vanilla's descriptor therefore keeps every existing reader correct while making the vanilla
	 * descriptor — the one two ecosystems' mods spell — exist again. Adding a second, vanilla-typed field instead
	 * would give fabric-api somewhere to write that nothing reads: the biome list would never be recomputed, the
	 * server would boot, and the modification would silently not apply. That is the failure this project has paid
	 * for more than once, and it is worse than the crash.
	 *
	 * <p>The rewrite is small and complete because the field has exactly FOUR instruction sites, all inside
	 * {@code ChunkGenerator} itself — verified by a constant-pool scan of the whole merged base and of both
	 * carriers, which find no other class naming it:
	 * <ul>
	 *   <li>{@code <init>}: {@code PUTFIELD} of {@code ClearableLazy.concurrentOf(...)} — descriptor only;</li>
	 *   <li>{@code validate()} and {@code applyBiomeDecoration(...)}: {@code GETFIELD} then
	 *       {@code ClearableLazy.get()} — retargeted to {@code Supplier.get()}, same descriptor, same stack;</li>
	 *   <li>{@code refreshFeaturesPerStep()}: {@code GETFIELD} then {@code ClearableLazy.invalidate()} — the one
	 *       use a plain {@code Supplier} cannot serve, so it goes to {@code KernelChunkGenerator.invalidate}.</li>
	 * </ul>
	 *
	 * <p>A bare {@code CHECKCAST} in {@code refreshFeaturesPerStep} would compile and look right, and then throw
	 * {@code ClassCastException} in worldgen the first time a Fabric modification had replaced the value — turning
	 * this fix into a different crash for the same mods. The guard also reports that state once, which is the only
	 * place either ecosystem could learn that MinecraftForge's refresh has become a no-op.
	 *
	 * <p>Stands down whole if it meets a site it does not recognise: a half-rewritten field is a
	 * {@code NoSuchFieldError} somewhere less legible than here. Idempotent by the same guard — after one pass no
	 * {@code ClearableLazy}-typed declaration remains, so the second pass finds nothing.
	 */
	static boolean giveFeaturesPerStepItsVanillaDescriptorBack(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.CHUNK_GENERATOR.equals(node.name)) return false;
		FieldNode field = null;
		for (FieldNode candidate : node.fields) {
			if (ForbricMergedBaseCompatTransformer.FEATURES_PER_STEP.equals(candidate.name) && ForbricMergedBaseCompatTransformer.CLEARABLE_LAZY_DESC.equals(candidate.desc)) {
				field = candidate;
			}
		}
		// Absent means vanilla's descriptor is already the only one — a rebuilt base, or this pass having run.
		if (field == null) return false;
		if (MergedBaseAsm.hasField(node, ForbricMergedBaseCompatTransformer.FEATURES_PER_STEP, ForbricMergedBaseCompatTransformer.SUPPLIER_DESC)) {
			// Both declarations present is the ParticleResources shape, not this one, and retyping would then
			// produce two fields with the same name AND descriptor, which is not a legal class.
			ForbricLog.warn("[Forbric/MergedBaseCompat] ChunkGenerator declares featuresPerStep with BOTH "
					+ "descriptors — that is the duplicate-field shape, which this repair must not touch");
			return false;
		}

		// Collect first, rewrite second: every site has to be one of the three known shapes, or none is changed.
		List<FieldInsnNode> sites = new ArrayList<>();
		List<MethodInsnNode> gets = new ArrayList<>();
		List<MethodInsnNode> invalidations = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode access) || !node.name.equals(access.owner)
						|| !ForbricMergedBaseCompatTransformer.FEATURES_PER_STEP.equals(access.name) || !ForbricMergedBaseCompatTransformer.CLEARABLE_LAZY_DESC.equals(access.desc)) {
					continue;
				}
				sites.add(access);
				if (access.getOpcode() == Opcodes.PUTFIELD) continue;
				if (access.getOpcode() != Opcodes.GETFIELD) {
					ForbricLog.warn("[Forbric/MergedBaseCompat] ChunkGenerator.featuresPerStep is accessed as a "
							+ "STATIC field in %s%s — not a shape this repair knows, so the field keeps "
							+ "MinecraftForge's descriptor and fabric-api's biome API stays broken",
							method.name, method.desc);
					return false;
				}
				AbstractInsnNode next = access.getNext();
				if (next instanceof MethodInsnNode call && ForbricMergedBaseCompatTransformer.CLEARABLE_LAZY.equals(call.owner)) {
					if ("get".equals(call.name) && "()Ljava/lang/Object;".equals(call.desc)) {
						gets.add(call);
						continue;
					}
					if ("invalidate".equals(call.name) && "()V".equals(call.desc)) {
						invalidations.add(call);
						continue;
					}
				}
				ForbricLog.warn("[Forbric/MergedBaseCompat] ChunkGenerator.featuresPerStep is read in %s%s and then "
						+ "used in a way this repair does not recognise — standing down whole rather than leaving "
						+ "the field half-retyped", method.name, method.desc);
				return false;
			}
		}
		if (sites.isEmpty()) return false;

		field.desc = ForbricMergedBaseCompatTransformer.SUPPLIER_DESC;
		// And the ACCESS the descriptor implies, which is not a tidy-up. fabric-api asks for exactly this field by
		// (owner, name, DESCRIPTOR) in fabric-biome-api-v1.classtweaker — "accessible" and "mutable" — and the
		// kernel applies class tweakers in the ACCESS phase, one phase BEFORE this one. So the request could not
		// have matched the ClearableLazy-typed declaration and the field is still private final here. Restoring
		// the descriptor alone therefore does not fix the boot, it only changes which error ends it:
		// NoSuchFieldError becomes IllegalAccessError, at the same cross-class PUTFIELD in BiomeModificationImpl.
		field.access = (field.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL))
				| Opcodes.ACC_PUBLIC;
		// The generic signature is metadata, but a stale one contradicts the descriptor for anything that reads
		// both (reflection, and this project's own artifact scans). Swap the prefix when it is the expected shape.
		if (field.signature != null) {
			String lazyPrefix = "L" + ForbricMergedBaseCompatTransformer.CLEARABLE_LAZY + "<";
			field.signature = field.signature.startsWith(lazyPrefix)
					? ForbricMergedBaseCompatTransformer.SUPPLIER_DESC.substring(0, ForbricMergedBaseCompatTransformer.SUPPLIER_DESC.length() - 1) + "<"
							+ field.signature.substring(lazyPrefix.length())
					: null;
		}
		for (FieldInsnNode access : sites) {
			access.desc = ForbricMergedBaseCompatTransformer.SUPPLIER_DESC;
		}
		for (MethodInsnNode get : gets) {
			get.owner = ForbricMergedBaseCompatTransformer.SUPPLIER;
			get.itf = true;
		}
		for (MethodInsnNode invalidate : invalidations) {
			// GETFIELD leaves exactly the receiver on the stack, which is this static call's only argument, so the
			// replacement is one instruction for one instruction: no stack depth change, no frame to recompute.
			invalidate.setOpcode(Opcodes.INVOKESTATIC);
			invalidate.owner = ForbricMergedBaseCompatTransformer.KERNEL_CHUNK_GENERATOR;
			invalidate.name = "invalidate";
			invalidate.desc = "(" + ForbricMergedBaseCompatTransformer.SUPPLIER_DESC + ")V";
			invalidate.itf = false;
		}

		ForbricLog.warn("[Forbric/MergedBaseCompat] ChunkGenerator.featuresPerStep carried MinecraftForge's "
				+ "ClearableLazy descriptor and vanilla's had stopped existing, so fabric-api's biome API — which "
				+ "writes that field directly — threw NoSuchFieldError and the server did not start. The field is "
				+ "vanilla-typed again (%d access site(s), %d read(s) retargeted, %d invalidation(s) guarded)",
				sites.size(), gets.size(), invalidations.size());
		return true;
	}


	/**
	 * Lets monster rooms generate again, by giving the NeoForge data map a vanilla fallback.
	 *
	 * <p>The merged {@code MonsterRoomFeature.randomEntityId} is two instructions:
	 * {@code invokestatic MonsterRoomHooks.getRandomMonsterRoomMob}. That reads a static {@code WeightedList} which
	 * only a {@code DataMapsUpdatedEvent} listener fills, and nothing in a Forbric instance had ever loaded a data
	 * map — a constant-pool scan of the whole merged base finds {@code DataMapLoader} named by nothing at all,
	 * because the merge kept MinecraftForge's {@code ReloadableServerResources}. So the list was null and the
	 * feature threw.
	 *
	 * <p>The kernel's previous answer was a {@code MethodBodyNeuter} on {@code MonsterRoomFeature.place}, which
	 * does not fail — it means no dungeon, and therefore no spawner and no dungeon chest, in EVERY world every
	 * player generates, with or without mods. A whole piece of vanilla, switched off silently, for everyone.
	 *
	 * <p>{@link net.forbric.kernel.runtime.KernelNeoWorldgen} now loads the data maps for real, so the primary
	 * path works and a NeoForge mod's additions count. This redirect is what makes that recoverable rather than
	 * load-bearing: when the data map is missing anyway, dungeons still generate from vanilla's own set. The two
	 * sets are the same distribution — vanilla's {@code MOBS} array is {@code {SKELETON, ZOMBIE, ZOMBIE, SPIDER}}
	 * and NeoForge's shipped data map is skeleton 100 / spider 100 / zombie 200 — so the fallback is vanilla's
	 * behaviour and not an approximation of it.
	 *
	 * <p>One instruction for one: the call is static, takes the same argument and returns the same type, so
	 * nothing on the stack or in a frame moves.
	 */
	static boolean letDungeonsGenerateWithoutTheDataMap(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.MONSTER_ROOM_FEATURE.equals(node.name)) return false;
		int redirected = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !ForbricMergedBaseCompatTransformer.MONSTER_ROOM_HOOKS.equals(call.owner)
						|| !"getRandomMonsterRoomMob".equals(call.name)
						|| !ForbricMergedBaseCompatTransformer.RANDOM_MONSTER_ROOM_MOB.equals(call.desc)) {
					continue;
				}
				call.owner = ForbricMergedBaseCompatTransformer.KERNEL_NEO_WORLDGEN;
				call.name = "randomMonsterRoomMob";
				redirected++;
			}
		}
		if (redirected == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] MonsterRoomFeature now picks its mob through the kernel "
				+ "(%d call site(s)) — NeoForge's data map when it has one, vanilla's own set when it does not. "
				+ "The alternative was the neutered place() this replaces, which meant no dungeon in any world",
				redirected);
		return true;
	}


	/**
	 * Puts the game's random sources back in double precision.
	 *
	 * <p>Vanilla's two {@code nextDouble()} bodies scale 53 random bits by 2^-53 in double:
	 * {@code nextBits(53); l2d; ldc2_w 1.1102230246251565E-16; dmul}. The merged base does it in FLOAT —
	 * {@code l2f; ldc 1.110223E-16f; fmul; f2d} — in both {@code XoroshiroRandomSource.nextDouble()} and the
	 * {@code BitRandomSource.nextDouble()} default that {@code LegacyRandomSource} and {@code WorldgenRandom}
	 * inherit. The constant is right (2^-53 is exact as a float); the {@code l2f} is not, because it crushes a
	 * 53-bit mantissa into 24.
	 *
	 * <p>Two costs, and the second one is a contract violation rather than a rounding difference:
	 * <ul>
	 * <li>EVERY sample differs from vanilla's — measured over a million draws, one million differed, worst
	 * relative error 5.95e-8. {@code ImprovedNoise}'s constructor spends three {@code nextDouble() * 256.0} calls
	 * on {@code xo/yo/zo}, so every Perlin octave's origin is displaced and the whole density field moves with it.
	 * A same-seed A/B against pure vanilla 26.2 (both sides run twice, because vanilla's own block output is only
	 * reproducible where features do not read their neighbours) measured it: biomes differ in 11 of 1764 chunks
	 * and heightmaps in 90 of 400 fully generated ones, where vanilla against itself differs in 0 and 10.</li>
	 * <li>{@code nextDouble()} can return exactly {@code 1.0}, for every {@code bits >= 9007198986305536} — about
	 * one draw in 2^25. Every caller in the game assumes the half-open range; an index computed as
	 * {@code (int)(nextDouble() * size)} is then off the end of its array.</li>
	 * </ul>
	 *
	 * <p>This is not a patch either ecosystem wrote. {@code patched-mc-forge-26.2.jar} carries vanilla's
	 * {@code l2d/dmul}; {@code patched-mc-neoforge-26.2.jar} carries the float form, which is what NeoForge's
	 * decompile-recompile pipeline emitted, and the byte merge kept the NeoForge body. It names no class from
	 * either ecosystem, so {@code merge-conflicts.txt} — which reports conflicts by REFERENCE, on purpose — cannot
	 * see it and never did. That is the general shape to watch for: a purely numeric method can be re-typed by the
	 * pipeline and leave no trace in the conflict ledger.
	 *
	 * <p>Matched by SHAPE across the whole base rather than by a list of two class names, because the pipeline
	 * decides where this lands, not us; the two known sources are declared as REQUIRED anchors so a rebuild that
	 * moves or fixes them is reported rather than passed over in silence.
	 *
	 * <p>Stack depth is the one thing that moves: {@code l2f/fmul} peaks at two slots where {@code l2d/dmul} needs
	 * four. No branch is added and no frame changes, so widening {@code maxStack} is the whole adjustment.
	 */
	static boolean restoreDoublePrecisionToTheRandomSources(ClassNode node) {
		if (!node.name.startsWith("net/minecraft/") || !ForbricMergedBaseCompatTransformer.randomSourcePrecisionEnabled()) return false;
		int repaired = 0;
		List<String> methods = new ArrayList<>();
		for (MethodNode method : node.methods) {
			boolean touched = false;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() != Opcodes.L2F) continue;
				AbstractInsnNode constant = MergedBaseAsm.nextReal(insn);
				if (!(constant instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Float scale)
						|| scale.floatValue() != ForbricMergedBaseCompatTransformer.DOUBLE_UNIT_AS_FLOAT) {
					continue;
				}
				AbstractInsnNode multiply = MergedBaseAsm.nextReal(constant);
				if (multiply == null || multiply.getOpcode() != Opcodes.FMUL) continue;
				AbstractInsnNode widen = MergedBaseAsm.nextReal(multiply);
				if (widen == null || widen.getOpcode() != Opcodes.F2D) continue;

				InsnList code = method.instructions;
				InsnNode inDouble = new InsnNode(Opcodes.DMUL);
				code.set(insn, new InsnNode(Opcodes.L2D));
				code.set(constant, new LdcInsnNode(ForbricMergedBaseCompatTransformer.DOUBLE_UNIT));
				code.set(multiply, inDouble);
				code.remove(widen);
				insn = inDouble;
				touched = true;
				repaired++;
			}
			if (touched) {
				method.maxStack += 2;
				methods.add(method.name + method.desc);
			}
		}
		if (repaired == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] %s scales its random bits in double again (%d site(s): %s) — the "
				+ "merged body rounded through float, which displaces every noise octave's origin and lets "
				+ "nextDouble() return exactly 1.0",
				node.name.replace('/', '.'), repaired, String.join(", ", methods));
		return true;
	}


	/**
	 * Restores the radians-to-degrees constant vanilla folded, which the merged base recomputes at run time.
	 *
	 * <p>Vanilla's source multiplies by a compile-time constant: {@code (double)(180.0F / (float)Math.PI)}, which
	 * javac folds in FLOAT and widens, giving {@code ldc2_w 57.2957763671875; dmul}. The merged base instead
	 * carries the expression — {@code ldc2_w 180.0; dmul; ldc2_w 3.1415927410125732; ddiv} — and evaluates it in
	 * DOUBLE every time, which is a different number: 57.29577791868205. They differ by 1.55e-6, a relative
	 * 2.7e-8, and the merged one is the more accurate of the two. Accuracy is not the question; being the game
	 * the same seed and the same inputs produce elsewhere is.
	 *
	 * <p>45 sites across 31 methods, and they are the ones that turn a direction into a rotation:
	 * {@code Entity.lookAt}, {@code Mob.lookAt}, {@code MoveControl.tick} and its flying, swimming and
	 * mob-specific siblings, {@code LookControl.getYRotD}, {@code Projectile.shoot} and {@code updateRotation},
	 * {@code ProjectileUtil.rotateTowardsMovement}, {@code CommandSourceStack.facing}, the dragon phases,
	 * {@code WitherBoss.aiStep}, {@code SignBlockEntity.isFacingFrontText}. Vanilla 26.2 has ZERO sites of this
	 * shape; the merged base has 45.
	 *
	 * <p>Same origin as {@link #restoreDoublePrecisionToTheRandomSources(ClassNode)} and the same blind spot:
	 * NeoForge's decompile-recompile pipeline wrote the folded constant back out as its expression, the byte
	 * merge kept that body, and because the method names no class from any ecosystem,
	 * {@code merge-conflicts.txt} — which reports conflicts by reference — never mentioned it. A differential
	 * census of all 94,202 shared methods, normalised for everything a recompile may legally change, found
	 * exactly two families of this kind: that one and this one.
	 *
	 * <p>Four instructions become two, the multiply is reused where it stands, and the peak stack only falls, so
	 * nothing about the frame needs adjusting.
	 */
	static boolean convertRadiansWithVanillasFoldedConstant(ClassNode node) {
		if (!node.name.startsWith("net/minecraft/")) return false;
		int folded = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof LdcInsnNode degrees) || !Double.valueOf(ForbricMergedBaseCompatTransformer.HALF_TURN_IN_DEGREES).equals(degrees.cst)) {
					continue;
				}
				AbstractInsnNode multiply = MergedBaseAsm.nextReal(insn);
				if (multiply == null || multiply.getOpcode() != Opcodes.DMUL) continue;
				AbstractInsnNode circle = MergedBaseAsm.nextReal(multiply);
				if (!(circle instanceof LdcInsnNode pi) || !Double.valueOf(ForbricMergedBaseCompatTransformer.PI_AS_FLOAT).equals(pi.cst)) continue;
				AbstractInsnNode divide = MergedBaseAsm.nextReal(circle);
				if (divide == null || divide.getOpcode() != Opcodes.DDIV) continue;

				degrees.cst = ForbricMergedBaseCompatTransformer.RADIANS_TO_DEGREES;
				method.instructions.remove(circle);
				method.instructions.remove(divide);
				insn = multiply;
				folded++;
			}
		}
		if (folded == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] %s turns radians into degrees by vanilla's folded constant again "
				+ "(%d site(s)) — the merged body divided by pi at run time, which is a different number in the "
				+ "eighth digit and moves every angle computed from a vector",
				node.name.replace('/', '.'), folded);
		return true;
	}


	/**
	 * Saves the heightmaps vanilla saves, and no others.
	 *
	 * <p>NeoForge gives {@code ChunkStatus} a second heightmap set — {@code chunkSaveHeightmaps}, which is
	 * {@code heightmapsAfter} plus {@code WORLD_SURFACE_WG} and {@code OCEAN_FLOOR_WG} for every status that is
	 * not a full chunk — and points all three of {@code SerializableChunkData}'s uses at it. MinecraftForge's
	 * patched jar does not; vanilla does not. So this is NeoForge's decision, not the pipeline's, and unlike its
	 * other decisions it changes what the world looks like.
	 *
	 * <p>The cost is not the extra bytes. Those two are WORLDGEN heightmaps: {@code ProtoChunk.setBlockState}
	 * stops maintaining them once a chunk passes CARVERS, so from that point they are a snapshot, and vanilla's
	 * answer is to never write them — a reloaded chunk rebuilds them from the blocks it actually has. Written and
	 * read back, they come back stale, and {@code PlacementUtils.HEIGHTMAP_WORLD_SURFACE} and
	 * {@code HEIGHTMAP_TOP_SOLID} are exactly what decide the Y a decoration is placed at. A chunk that was saved
	 * half-generated, unloaded and reloaded then decorates against a height that is no longer true.
	 *
	 * <p>Measured, on one seed, zero mods, five vanilla worlds against five Forbric ones: after the other two
	 * repairs the ONLY difference left that survives the noise filter is five chunks whose {@code WORLD_SURFACE}
	 * heightmap differs, and every one of them is a dead bush — 7 of 5,079 — placed on identical terracotta in
	 * identical badlands, in a chunk near spawn that the server had saved and reloaded. Blocks, block entities,
	 * biomes and structure starts are all identical.
	 *
	 * <p>One instruction's operand: the getter reads the vanilla-shaped field instead of NeoForge's widened one,
	 * which leaves both the write path and the read path agreeing with vanilla. The field and its constructor
	 * stay where they are, so anything that asks NeoForge's own accessor for them still gets an answer.
	 */
	static boolean saveTheHeightmapsVanillaSaves(ClassNode node) {
		if (!ForbricMergedBaseCompatTransformer.CHUNK_STATUS.equals(node.name) || !ForbricMergedBaseCompatTransformer.savedHeightmapsEnabled()) return false;
		if (!MergedBaseAsm.hasField(node, ForbricMergedBaseCompatTransformer.HEIGHTMAPS_AFTER, ForbricMergedBaseCompatTransformer.ENUM_SET_DESC)) return false;
		int rebased = 0;
		for (MethodNode method : node.methods) {
			if (!"getChunkSaveHeightmaps".equals(method.name)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode read) || read.getOpcode() != Opcodes.GETFIELD
						|| !ForbricMergedBaseCompatTransformer.CHUNK_STATUS.equals(read.owner) || !ForbricMergedBaseCompatTransformer.CHUNK_SAVE_HEIGHTMAPS.equals(read.name)) {
					continue;
				}
				read.name = ForbricMergedBaseCompatTransformer.HEIGHTMAPS_AFTER;
				rebased++;
			}
		}
		if (rebased == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] ChunkStatus now reports vanilla's saved-heightmap set (%d read(s)) "
				+ "— NeoForge widened it with the two worldgen heightmaps, which an unfinished chunk then reloads "
				+ "stale, and those are what decide the Y a decoration is placed at", rebased);
		return true;
	}
}
