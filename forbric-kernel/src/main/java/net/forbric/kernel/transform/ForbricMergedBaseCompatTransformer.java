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
 * Repairs class-local bytecode invariants that can drift when two patched Minecraft bases are merged.
 *
 * <p>The repairs themselves live in the {@code MergedBase*Repair} classes. This class keeps the order,
 * the claims, and the anchors. A repair that moves must stay in {@link #REPAIRS} and in {@code transform}
 * in that same order.
 */
public final class ForbricMergedBaseCompatTransformer implements ClassTransformer {
	/**
	 * Reads another class's bytes, for the one repair that has to look up the superclass chain. Null when the
	 * transformer was built without one, in which case that repair stands down rather than guessing.
	 */
	private final java.util.function.Function<String, byte[]> classBytes;

	/** Without a resolver: every repair except the shadowing-override one, which needs to read other classes. */
	public ForbricMergedBaseCompatTransformer() {
		this(null);
	}

	public ForbricMergedBaseCompatTransformer(java.util.function.Function<String, byte[]> classBytes) {
		this.classBytes = classBytes;
	}

	@Override
	public String name() {
		return "forbric-merged-base-compat";
	}

	@Override
	public AnchorSet anchors() {
		// Independent repairs behind one `changed` flag -- dungeon generation, key mappings, the particle map,
		// default attributes, the save on teardown. Each one can stop applying on its own, and a single
		// class-level answer cannot see that. This is the largest reservoir of the failure this mechanism
		// exists for, and it needs one claim per repair rather than one anchor per class.
		//
		// COUNTED, never written down. This sentence said "47" and the comment above it said "Forty" while
		// REPAIRS held 49: two self-descriptions that drifted because nothing compared them to anything, in
		// the one class whose entire job is that a silent change gets noticed. The list is the number.
		return AnchorSet.scanned(REPAIRS.size() + " independent repairs across the whole base, each needing its own claim");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		return transform(className, classBytes, context, ClaimReporter.NONE);
	}

	/** The repairs {@link #transform} runs, in its order; a test pins the two lists against each other. */
	static final List<String> REPAIRS = List.of("repairLambdaBootstrapHandles", "addBlockStateModelConflictResolvers", "addBlockStateAppearanceResolver", "addMissingForgeFluidTypeBridge", "addMissingForgeKeyMappingLookupInitializer", "routeKeyMappingClickToPopulatedLookup", "giveKeyMappingItsMinecraftForgeFace", "giveTheVanillaParticleMapAViewOfTheLiveOne", "giveFeaturesPerStepItsVanillaDescriptorBack", "letDungeonsGenerateWithoutTheDataMap", "restoreDoublePrecisionToTheRandomSources", "convertRadiansWithVanillasFoldedConstant", "saveTheHeightmapsVanillaSaves", "guardNeoForgesWorldModifierPass", "letForeignResourceConditionsThrough", "letForeignResourceConditionsThroughMinecraftForge", "letFabricResourceConditionsDecide", "translateAGuestsPrivateSkipMarker", "serveDefaultAttributesBothEcosystems", "restoreForgeClientInit", "restoreForgeGeometryReload", "nameTheReloadListenersNeoForgeRefusesToName", "dropInterfaceDefaultShadowingOverrides", "tolerateEmptyCreativeTabStacks", "routePlaceItemHookToNeoForge", "bridgeOrphanedPipRenderers", "keepForgeOutboundProtocolCurrent", "surviveTheMissingForgeModelDataManager", "dropTheWindowTitlesLoaderBrand", "keepTheSaveOffTheTeardownsFailurePath", "postNeoForgesItemTooltipEvent", "askNeoForgeWhatAnItemsAttributesAre", "readTheSpawnReasonThatIsActuallyWritten", "giveTheUnwrittenLoggerAValue", "addTheMissingCapabilityLifecycleStubs", "addTheMissingNbtBuilderFactory", "postMinecraftForgesReloadListenerEvent", "giveMinecraftForgesReloadEventItsConditionContext", "letMinecraftForgeIngredientTypesDecode", "letMinecraftForgeFluidsChooseTheirModel", "giveMinecraftForgesParticleLookupItsFirstVariant", "dropStubsThatBypassARealSuperclassMethod", "inlineTheSwitchMapTheMergeLost", "vetoUnjudgeableOverlayConditions", "hideTheLegacyLootModifierIndexFromTheDirectoryScan", "letModdedFeatureFlagsRegister", "dropTheKeyModifierSuffixBeforeParsingAKeyName", "letTheAtlasLowerItsMipLevelLikeVanilla", "wrapTheStreamsVanillaWraps", "returnFromANestedBootstrapBeforeItsTail", "letBothEcosystemsSetBurnTime", "letMinecraftForgeSeeSpawnerMobs", "letMinecraftForgeAddPackFinders");

	static final String NEO_EVENT_HOOKS_BINARY = "net.neoforged.neoforge.event.EventHooks";

	/**
	 * One claim per repair, in {@link #REPAIRS} order. A repair with one fixed target declares it REQUIRED with
	 * the cost of its silence; one that scans by shape declares {@link AnchorSet#scanned}. Client-only targets
	 * are simply never loaded on a dedicated server, which the ledger reports as absent, not missed. The two
	 * repairs behind {@code -Dforbric.forgeClientInit} stand down with it, so switching them off is not a Miss.
	 */
	@Override
	public List<Claim> claims() {
		boolean clientInit = !"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"));
		List<Claim> out = new ArrayList<>();
		out.add(scanned("repairLambdaBootstrapHandles", "any class whose invokedynamic still names the old loader's hook owners"));
		out.add(fixed("addBlockStateModelConflictResolvers", "net/minecraft/client/renderer/block/dispatch/BlockStateModel",
				"every block model's geometry key and conflict resolver are gone — the merged BlockStateModel lacks the methods both families call"));
		out.add(scanned("addBlockStateAppearanceResolver",
				"net.minecraft.world.level.block.Block and ...block.state.BlockState, which each inherit "
						+ "getAppearance as a default from BOTH NeoForge and fabric-api and declare neither, so the "
						+ "first mod to ask a neighbour what it looks like — any connected-texture mod — dies on "
						+ "IncompatibleClassChangeError mid-frame"));
		out.add(scanned("addMissingForgeFluidTypeBridge", "every concrete fluid under net.minecraft.world.level.material implementing NeoForge's IFluidExtension"));
		out.add(fixed("addMissingForgeKeyMappingLookupInitializer", KEY_MAPPING,
				"MinecraftForge's KeyMapping.MAP is never initialised — every traditional-Forge key registration NPEs"));
		out.add(fixed("routeKeyMappingClickToPopulatedLookup", KEY_MAPPING,
				"key presses are looked up in the lookup registration never populated — MinecraftForge mods' keys never fire"));
		out.add(fixed("giveKeyMappingItsMinecraftForgeFace", KEY_MAPPING,
				"KeyMapping lacks the MinecraftForge-typed accessors — a Forge mod setting a conflict context NoSuchMethodErrors"));
		out.add(fixed("giveTheVanillaParticleMapAViewOfTheLiveOne", PARTICLE_RESOURCES,
				"the vanilla-typed particle provider map stays empty — particles registered the vanilla way never render"));
		out.add(fixed("giveFeaturesPerStepItsVanillaDescriptorBack", CHUNK_GENERATOR,
				"ChunkGenerator.featuresPerStep keeps MinecraftForge's descriptor — the server cannot start (NoSuchFieldError)"));
		out.add(fixed("letDungeonsGenerateWithoutTheDataMap", MONSTER_ROOM_FEATURE,
				"monster rooms never generate — the NeoForge data map they ask has no vanilla fallback"));
		out.add(randomSourcePrecisionEnabled()
				? new Claim(claimId("restoreDoublePrecisionToTheRandomSources"), AnchorSet.of(
						new AnchorSet.Anchor(XOROSHIRO_RANDOM_SOURCE.replace('/', '.'), AnchorSet.Severity.REQUIRED,
								"every noise octave's origin is off — the merged nextDouble() rounds through float, so no "
										+ "world generates the way the same seed does in vanilla"),
						new AnchorSet.Anchor(BIT_RANDOM_SOURCE.replace('/', '.'), AnchorSet.Severity.REQUIRED,
								"WorldgenRandom's nextDouble() rounds through float and can return exactly 1.0 — out of "
										+ "the [0,1) range every caller assumes")))
				: scanned("restoreDoublePrecisionToTheRandomSources", "-D" + RANDOM_PRECISION_PROPERTY + "=off"));
		out.add(fixed("convertRadiansWithVanillasFoldedConstant", "net/minecraft/world/entity/Entity",
				"every angle the game computes from a vector is off in the eighth digit — the merged base divides by "
						+ "pi at run time where vanilla multiplies by a constant it folded in float"));
		out.add(savedHeightmapsEnabled()
				? fixed("saveTheHeightmapsVanillaSaves", CHUNK_STATUS,
						"an unfinished chunk is saved with the two worldgen heightmaps vanilla never persists, and "
								+ "reloads with them stale — a feature placed on WORLD_SURFACE_WG then lands somewhere "
								+ "vanilla would not put it")
				: scanned("saveTheHeightmapsVanillaSaves", "-D" + SAVED_HEIGHTMAPS_PROPERTY + "=off"));
		out.add(fixed("guardNeoForgesWorldModifierPass", NEO_SERVER_LIFECYCLE_HOOKS,
				"NeoForge's biome/structure modifier pass is neutered — every neoforge:biome_modifier does nothing"));
		out.add(fixed("letForeignResourceConditionsThrough", ICONDITION,
				"another ecosystem's condition type fails NeoForge's evaluator and the whole registry load with it"));
		out.add(fixed("letForeignResourceConditionsThroughMinecraftForge", FORGE_ICONDITION,
				"another ecosystem's condition type fails MinecraftForge's evaluator and the whole registry load with it"));
		out.add(fixed("letFabricResourceConditionsDecide", CONDITIONAL_OPS,
				"fabric:load_conditions has no evaluator — a Fabric mod's conditional data files all load"));
		out.add(fixed("translateAGuestsPrivateSkipMarker", JSON_RELOAD_LISTENER,
				"fabric-api's skip marker reaches the merged reader's cast — the datapack load dies (\"can't proceed with server load\")"));
		out.add(fixed("serveDefaultAttributesBothEcosystems", DEFAULT_ATTRIBUTES,
				"DefaultAttributes reads only the ecosystem that won the merge — the other's entities \"have no attributes\""));
		out.add(clientInit ? fixed("restoreForgeClientInit", "net/minecraft/client/Minecraft",
				"ForgeHooksClient.initClientHooks never runs — traditional-Forge key mappings, renderers and layers are never registered")
				: scanned("restoreForgeClientInit", "switched off by -Dforbric.forgeClientInit=off"));
		out.add(clientInit ? fixed("restoreForgeGeometryReload", "net/minecraft/client/resources/model/ModelManager",
				"MinecraftForge's geometry loaders never reload — Forge OBJ/custom models are missing")
				: scanned("restoreForgeGeometryReload", "switched off by -Dforbric.forgeClientInit=off"));
		out.add(fixed("nameTheReloadListenersNeoForgeRefusesToName", ADD_CLIENT_RELOAD_LISTENERS,
				"a Fabric mod's client reload listener kills the client — NeoForge refuses to name it"));
		out.add(scanned("dropInterfaceDefaultShadowingOverrides", "every net.minecraft.client.gui class implementing ContainerEventHandler"));
		out.add(fixed("tolerateEmptyCreativeTabStacks", NEO_EVENT_HOOKS_BINARY.replace('.', '/'),
				"one empty stack from any mod aborts the whole creative menu"));
		out.add(fixed("routePlaceItemHookToNeoForge", ITEM_STACK,
				"placing any block ClassCastExceptions on the server thread — ItemStack.useOn drains a NeoForge-typed snapshot list as MinecraftForge's"));
		out.add(fixed("bridgeOrphanedPipRenderers", GUI_RENDERER,
				"a picture-in-picture renderer registered the vanilla way never draws"));
		out.add(fixed("keepForgeOutboundProtocolCurrent", "net/minecraft/network/Connection",
				"MinecraftForge's channels pick their packet type from a protocol field nothing writes — Forge networking sends the wrong packet type"));
		out.add(fixed("surviveTheMissingForgeModelDataManager", "net/minecraft/client/renderer/extract/LevelExtractor",
				"the block-breaking overlay crashes the render frame on MinecraftForge's absent model-data manager"));
		out.add(fixed("dropTheWindowTitlesLoaderBrand", "net/minecraft/client/Minecraft",
				"the window title carries another loader's brand"));
		out.add(fixed("keepTheSaveOffTheTeardownsFailurePath", INTEGRATED_SERVER,
				"a throw in IntegratedServer.teardownPublishedState costs the world save"));
		out.add(fixed("postNeoForgesItemTooltipEvent", ITEM_STACK,
				"NeoForge mods cannot add a line to any item's tooltip — the merged getTooltipLines posts only MinecraftForge's event"));
		out.add(fixed("askNeoForgeWhatAnItemsAttributesAre", ITEM_STACK,
				"an item's attributes are read off the raw component — elytra flight and every NeoForge attribute modifier stop working"));
		out.add(fixed("readTheSpawnReasonThatIsActuallyWritten", "net/minecraft/world/entity/Mob",
				"Mob.getSpawnReason() reads a field the game never writes — spawn-reason logic sees null"));
		out.add(scanned("giveTheUnwrittenLoggerAValue", "any class with a static final Logger the merge left unassigned"));
		// The capability composition (E) runs first in the same phase and composes the three roots itself; the
		// stubs are its fallback and are expected to find nothing while it is on. Measured on gate-m9: all three
		// declined, exactly because the composed methods were already there.
		out.add(ForgeCapabilityCompositionTransformer.enabled()
				? scanned("addTheMissingCapabilityLifecycleStubs", "the capability composition composes the roots first; these stubs are its fallback")
				: new Claim(claimId("addTheMissingCapabilityLifecycleStubs"), AnchorSet.of(
						capabilityRoot("net/minecraft/world/entity/Entity"), capabilityRoot("net/minecraft/world/level/block/entity/BlockEntity"),
						capabilityRoot("net/minecraft/world/level/Level"))));
		out.add(fixed("addTheMissingNbtBuilderFactory", "net/minecraft/nbt/CompoundTag",
				"CompoundTag.builder() is gone — IForgeBlockPos.toCompoundTag and ForgeHooks.createEmptyStructure NoSuchMethodError"));
		out.add(fixed("postMinecraftForgesReloadListenerEvent", RELOADABLE_SERVER_RESOURCES,
				"MinecraftForge's AddReloadListenerEvent is never posted — traditional-Forge JSON data loaders never register"));
		out.add(fixed("giveMinecraftForgesReloadEventItsConditionContext", FORGE_RELOAD_EVENT,
				"AddReloadListenerEvent.getConditionContext() NoSuchMethodErrors the first Forge data loader that asks"));
		out.add(fixed("letMinecraftForgeIngredientTypesDecode", "net/minecraft/world/item/crafting/Ingredient",
				"MinecraftForge ingredient types (forge:intersection, …) fail to parse — every recipe using one is dropped"));
		out.add(fixed("letMinecraftForgeFluidsChooseTheirModel", FLUID_RENDERER,
				"a MinecraftForge fluid renders with vanilla water's model and tint"));
		out.add(fixed("giveMinecraftForgesParticleLookupItsFirstVariant", WEIGHTED_VARIANTS,
				"WeightedVariants.first is never written — MinecraftForge's particle lookup reads null"));
		out.add(scanned("dropStubsThatBypassARealSuperclassMethod", "any class carrying a measured merge stub that shadows a real superclass method"));
		out.add(fixed("inlineTheSwitchMapTheMergeLost", LOST_SWITCH_MAPS.get(0).user(),
				"AbstractFurnaceBlockEntity's Direction switch NoSuchFieldErrors on the $SwitchMap the merge lost — furnaces cannot be interacted with"));
		out.add(fixed("vetoUnjudgeableOverlayConditions", OVERLAY_ENTRY,
				"a pack.mcmeta overlay gated by a condition no evaluator here can judge is mounted anyway"));
		out.add(new Claim(claimId("hideTheLegacyLootModifierIndexFromTheDirectoryScan"), AnchorSet.of(
				new AnchorSet.Anchor(LOOT_MODIFIER_MANAGER_NEO.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"NeoForge's loot-modifier manager parse-fails MinecraftForge's legacy index file on every reload"),
				new AnchorSet.Anchor(LOOT_MODIFIER_MANAGER_FORGE.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"MinecraftForge's loot-modifier manager parse-fails its own index as a modifier on every reload"))));
		out.add(fixed("letModdedFeatureFlagsRegister", FEATURE_FLAGS,
				"NeoForge mods' declared feature flags are never registered — a mod asking for its own flag dies in its static "
						+ "initialiser and its datapack then fails the whole registry load"));
		// Both of these are switched off by their own property, and a claim that stays REQUIRED while its repair is
		// off reports the switch as a broken anchor. The lesson is J11's: a conditional repair declares a
		// conditional claim, or the census stops meaning what it says.
		out.add(keyModifierSuffixEnabled()
				? fixed("dropTheKeyModifierSuffixBeforeParsingAKeyName", INPUT_CONSTANTS,
						"one modded key bound with a modifier throws out of options.txt parsing — the player loses EVERY setting")
				: scanned("dropTheKeyModifierSuffixBeforeParsingAKeyName", "-D" + KEY_SUFFIX_PROPERTY + "=off"));
		out.add(mipmapLoweringEnabled()
				? fixed("letTheAtlasLowerItsMipLevelLikeVanilla", SPRITE_LOADER,
						"an atlas holding a sprite smaller than the mip level allows fails to upload — the FIRST resource "
								+ "reload dies, every pack is dropped, and the client sits on a black screen with no further log")
				: scanned("letTheAtlasLowerItsMipLevelLikeVanilla", "-D" + MIPMAP_PROPERTY + "=off"));
		out.add(fixed("wrapTheStreamsVanillaWraps", BOOTSTRAP,
				"System.out and System.err are never routed into log4j, so every line a mod PRINTS rather than logs "
						+ "is absent from latest.log — including the debug output a mod is told to turn on when it "
						+ "misbehaves"));
		out.add(fixed("returnFromANestedBootstrapBeforeItsTail", BOOTSTRAP,
				"MinecraftForge's ForgeRegistries re-enters Bootstrap.bootStrap() from inside the first one, so every "
						+ "mixin at its TAIL runs twice, the first time half-way through bootstrap — a Fabric mod that "
						+ "initialises there once (cristellib) throws and the server does not start"));
		out.add(fixed("letBothEcosystemsSetBurnTime", FUEL_VALUES,
				"NeoForge's FurnaceFuelBurnTimeEvent is never posted, so a NeoForge mod cannot change how long "
						+ "anything burns while a MinecraftForge one can"));
		out.add(fixed("letMinecraftForgeSeeSpawnerMobs", BASE_SPAWNER,
				"MobSpawnEvent$FinalizeSpawn is never posted, so a MinecraftForge mod can neither see nor refuse "
						+ "a mob a spawner produces"));
		out.add(scanned("letMinecraftForgeAddPackFinders",
				"AddPackFindersEvent is never posted, so a MinecraftForge mod's own data pack is never offered "
						+ "to any repository"));
		return List.copyOf(out);
	}

	private Claim fixed(String repair, String internalTarget, String cost) {
		return new Claim(claimId(repair), AnchorSet.of(new AnchorSet.Anchor(internalTarget.replace('/', '.'), AnchorSet.Severity.REQUIRED, cost)));
	}

	private Claim scanned(String repair, String why) {
		return new Claim(claimId(repair), AnchorSet.scanned(why));
	}

	static AnchorSet.Anchor capabilityRoot(String internal) {
		return new AnchorSet.Anchor(internal.replace('/', '.'), AnchorSet.Severity.REQUIRED,
				"the capability lifecycle stubs are missing on " + internal.substring(internal.lastIndexOf('/') + 1)
						+ " — its own merged code calls invalidateCaps/reviveCaps and NoSuchMethodErrors");
	}


	/** Reports {@code id} as applied when {@code applied}; the repair's own answer is returned unchanged. */
	private boolean claim(ClaimReporter reporter, String id, boolean applied) {
		if (applied) reporter.hit(claimId(id));
		return applied;
	}

	private String claimId(String repair) {
		return name() + "#" + repair;
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context, ClaimReporter reporter) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		try {
			boolean namedOldLoader = stillNamesTheOldLoader(classBytes);
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			boolean changed = false;
			changed |= claim(reporter, "repairLambdaBootstrapHandles", MergedBaseLambdaRepair.repairLambdaBootstrapHandles(node));
			changed |= claim(reporter, "addBlockStateModelConflictResolvers", MergedBaseBlockRepair.addBlockStateModelConflictResolvers(node));
			changed |= claim(reporter, "addBlockStateAppearanceResolver", MergedBaseBlockRepair.addBlockStateAppearanceResolver(node));
			changed |= claim(reporter, "addMissingForgeFluidTypeBridge", MergedBaseForgeBridgeRepair.addMissingForgeFluidTypeBridge(node));
			changed |= claim(reporter, "addMissingForgeKeyMappingLookupInitializer", MergedBaseKeyRepair.addMissingForgeKeyMappingLookupInitializer(node));
			changed |= claim(reporter, "routeKeyMappingClickToPopulatedLookup", MergedBaseKeyRepair.routeKeyMappingClickToPopulatedLookup(node));
			changed |= claim(reporter, "giveKeyMappingItsMinecraftForgeFace", MergedBaseKeyRepair.giveKeyMappingItsMinecraftForgeFace(node));
			changed |= claim(reporter, "giveTheVanillaParticleMapAViewOfTheLiveOne", MergedBaseParticleRepair.giveTheVanillaParticleMapAViewOfTheLiveOne(node));
			changed |= claim(reporter, "giveFeaturesPerStepItsVanillaDescriptorBack", MergedBaseWorldRepair.giveFeaturesPerStepItsVanillaDescriptorBack(node));
			changed |= claim(reporter, "letDungeonsGenerateWithoutTheDataMap", MergedBaseWorldRepair.letDungeonsGenerateWithoutTheDataMap(node));
			changed |= claim(reporter, "restoreDoublePrecisionToTheRandomSources", MergedBaseWorldRepair.restoreDoublePrecisionToTheRandomSources(node));
			changed |= claim(reporter, "convertRadiansWithVanillasFoldedConstant", MergedBaseWorldRepair.convertRadiansWithVanillasFoldedConstant(node));
			changed |= claim(reporter, "saveTheHeightmapsVanillaSaves", MergedBaseWorldRepair.saveTheHeightmapsVanillaSaves(node));
			changed |= claim(reporter, "guardNeoForgesWorldModifierPass", MergedBaseConditionRepair.guardNeoForgesWorldModifierPass(node));
			changed |= claim(reporter, "letForeignResourceConditionsThrough", MergedBaseConditionRepair.letForeignResourceConditionsThrough(node));
			changed |= claim(reporter, "letForeignResourceConditionsThroughMinecraftForge", MergedBaseConditionRepair.letForeignResourceConditionsThroughMinecraftForge(node));
			changed |= claim(reporter, "letFabricResourceConditionsDecide", MergedBaseConditionRepair.letFabricResourceConditionsDecide(node));
			changed |= claim(reporter, "translateAGuestsPrivateSkipMarker", MergedBaseConditionRepair.translateAGuestsPrivateSkipMarker(node));
			changed |= claim(reporter, "serveDefaultAttributesBothEcosystems", MergedBaseClientRepair.serveDefaultAttributesBothEcosystems(node));
			changed |= claim(reporter, "restoreForgeClientInit", restoreForgeClientInit(node));
			changed |= claim(reporter, "restoreForgeGeometryReload", restoreForgeGeometryReload(node));
			changed |= claim(reporter, "nameTheReloadListenersNeoForgeRefusesToName", MergedBaseClientRepair.nameTheReloadListenersNeoForgeRefusesToName(node));
			changed |= claim(reporter, "dropInterfaceDefaultShadowingOverrides", MergedBaseGuiRepair.dropInterfaceDefaultShadowingOverrides(node));
			changed |= claim(reporter, "tolerateEmptyCreativeTabStacks", MergedBaseGuiRepair.tolerateEmptyCreativeTabStacks(node));
			changed |= claim(reporter, "routePlaceItemHookToNeoForge", MergedBaseGuiRepair.routePlaceItemHookToNeoForge(node));
			changed |= claim(reporter, "bridgeOrphanedPipRenderers", MergedBaseRenderRepair.bridgeOrphanedPipRenderers(node));
			changed |= claim(reporter, "keepForgeOutboundProtocolCurrent", MergedBaseNetworkRepair.keepForgeOutboundProtocolCurrent(node));
			changed |= claim(reporter, "surviveTheMissingForgeModelDataManager", MergedBaseRenderRepair.surviveTheMissingForgeModelDataManager(node));
			changed |= claim(reporter, "dropTheWindowTitlesLoaderBrand", MergedBaseClientRepair.dropTheWindowTitlesLoaderBrand(node));
			changed |= claim(reporter, "keepTheSaveOffTheTeardownsFailurePath", MergedBaseSpawnRepair.keepTheSaveOffTheTeardownsFailurePath(node));
			changed |= claim(reporter, "postNeoForgesItemTooltipEvent", MergedBaseGuiRepair.postNeoForgesItemTooltipEvent(node));
			changed |= claim(reporter, "askNeoForgeWhatAnItemsAttributesAre", MergedBaseGuiRepair.askNeoForgeWhatAnItemsAttributesAre(node));
			changed |= claim(reporter, "readTheSpawnReasonThatIsActuallyWritten", MergedBaseSpawnRepair.readTheSpawnReasonThatIsActuallyWritten(node));
			changed |= claim(reporter, "giveTheUnwrittenLoggerAValue", MergedBaseCapabilityRepair.giveTheUnwrittenLoggerAValue(node));
			changed |= claim(reporter, "addTheMissingCapabilityLifecycleStubs", MergedBaseCapabilityRepair.addTheMissingCapabilityLifecycleStubs(node));
			changed |= claim(reporter, "addTheMissingNbtBuilderFactory", MergedBaseForgeBridgeRepair.addTheMissingNbtBuilderFactory(node));
			changed |= claim(reporter, "postMinecraftForgesReloadListenerEvent", MergedBaseForgeBridgeRepair.postMinecraftForgesReloadListenerEvent(node));
			changed |= claim(reporter, "giveMinecraftForgesReloadEventItsConditionContext", MergedBaseForgeBridgeRepair.giveMinecraftForgesReloadEventItsConditionContext(node));
			changed |= claim(reporter, "letMinecraftForgeIngredientTypesDecode", MergedBaseForgeBridgeRepair.letMinecraftForgeIngredientTypesDecode(node));
			changed |= claim(reporter, "letMinecraftForgeFluidsChooseTheirModel", MergedBaseForgeBridgeRepair.letMinecraftForgeFluidsChooseTheirModel(node));
			changed |= claim(reporter, "giveMinecraftForgesParticleLookupItsFirstVariant", MergedBaseForgeBridgeRepair.giveMinecraftForgesParticleLookupItsFirstVariant(node));
			changed |= claim(reporter, "dropStubsThatBypassARealSuperclassMethod", MergedBaseCapabilityRepair.dropStubsThatBypassARealSuperclassMethod(node, this.classBytes));
			changed |= claim(reporter, "inlineTheSwitchMapTheMergeLost", MergedBasePackRepair.inlineTheSwitchMapTheMergeLost(node));
			changed |= claim(reporter, "vetoUnjudgeableOverlayConditions", MergedBasePackRepair.vetoUnjudgeableOverlayConditions(node));
			changed |= claim(reporter, "hideTheLegacyLootModifierIndexFromTheDirectoryScan", MergedBasePackRepair.hideTheLegacyLootModifierIndexFromTheDirectoryScan(node));
			changed |= claim(reporter, "letModdedFeatureFlagsRegister", MergedBasePackRepair.letModdedFeatureFlagsRegister(node));
			changed |= claim(reporter, "dropTheKeyModifierSuffixBeforeParsingAKeyName",
					MergedBasePackRepair.dropTheKeyModifierSuffixBeforeParsingAKeyName(node));
			changed |= claim(reporter, "letTheAtlasLowerItsMipLevelLikeVanilla",
					MergedBasePackRepair.letTheAtlasLowerItsMipLevelLikeVanilla(node));
			changed |= claim(reporter, "wrapTheStreamsVanillaWraps", MergedBaseLambdaRepair.wrapTheStreamsVanillaWraps(node));
			changed |= claim(reporter, "returnFromANestedBootstrapBeforeItsTail", MergedBaseLambdaRepair.returnFromANestedBootstrapBeforeItsTail(node));
		changed |= claim(reporter, "letBothEcosystemsSetBurnTime", MergedBaseGuiRepair.letBothEcosystemsSetBurnTime(node));
		changed |= claim(reporter, "letMinecraftForgeSeeSpawnerMobs", MergedBaseSpawnRepair.letMinecraftForgeSeeSpawnerMobs(node));
		changed |= claim(reporter, "letMinecraftForgeAddPackFinders", MergedBaseSpawnRepair.letMinecraftForgeAddPackFinders(node));
			changed |= namedOldLoader && MergedBaseLegacyNames.adoptInteropHooksTheBaseStillNamesAfterTheOldLoader(node);

			byte[] result = classBytes;
			if (changed) {
				ClassWriter writer = new ClassWriter(0);
				node.accept(writer);
				result = writer.toByteArray();
			}
			if (namedOldLoader && stillNamesTheOldLoader(result)) {
				// Not fatal here, but it WILL be at link time, in a stack that points at the game rather than at
				// this transformer. Name it while the cause is still legible.
				ForbricLog.error("[Forbric/MergedBaseCompat] %s still names %s after adoption — a reference shape "
								+ "this pass does not rewrite. It will fail to link.",
						className, LEGACY_INTEROP_PACKAGE.replace('/', '.'));
			}
			return result;
		} catch (RuntimeException e) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] could not inspect " + className, e);
			return classBytes;
		}
	}

	static final String LEGACY_INTEROP_PACKAGE = "net/forbric/loader/impl/";

	static final String KEY_MAPPING = "net/minecraft/client/KeyMapping";
	static final String MF_CONTEXT = "Lnet/minecraftforge/client/settings/IKeyConflictContext;";
	static final String NEO_CONTEXT = "Lnet/neoforged/neoforge/client/settings/IKeyConflictContext;";
	static final String MF_MODIFIER = "Lnet/minecraftforge/client/settings/KeyModifier;";
	static final String NEO_MODIFIER = "Lnet/neoforged/neoforge/client/settings/KeyModifier;";
	static final String INPUT_KEY = "Lcom/mojang/blaze3d/platform/InputConstants$Key;";
	static final String KERNEL_KEYS = "net/forbric/kernel/runtime/KernelForgeKeyBindings";

	static final String PARTICLE_RESOURCES = "net/minecraft/client/particle/ParticleResources";

	static final String CHUNK_GENERATOR = "net/minecraft/world/level/chunk/ChunkGenerator";
	static final String FEATURES_PER_STEP = "featuresPerStep";
	static final String CLEARABLE_LAZY = "net/minecraftforge/common/util/ClearableLazy";
	static final String CLEARABLE_LAZY_DESC = "L" + CLEARABLE_LAZY + ";";
	static final String SUPPLIER = "java/util/function/Supplier";
	static final String SUPPLIER_DESC = "L" + SUPPLIER + ";";
	static final String KERNEL_CHUNK_GENERATOR = "net/forbric/kernel/runtime/KernelChunkGenerator";

	static final String KERNEL_NEO_WORLDGEN = "net/forbric/kernel/runtime/KernelNeoWorldgen";
	static final String KERNEL_FUEL_VALUES = "net/forbric/kernel/runtime/KernelFuelValues";
	static final String KERNEL_SPAWNER_FINALIZE = "net/forbric/kernel/runtime/KernelSpawnerFinalize";
	static final String KERNEL_PACK_FINDERS = "net/forbric/kernel/runtime/KernelPackFinders";
	static final String NEO_RESOURCE_PACK_LOADER = "net/neoforged/neoforge/resource/ResourcePackLoader";
	static final String BASE_SPAWNER = "net/minecraft/world/level/BaseSpawner";
	static final String NEO_EVENT_HOOKS = "net/neoforged/neoforge/event/EventHooks";
	static final String FUEL_VALUES = "net/minecraft/world/level/block/entity/FuelValues";
	static final String FORGE_BURN_TIME_DESC =
			"(Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/world/item/crafting/RecipeType;)I";
	static final String KERNEL_BURN_TIME_DESC =
			"(Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/world/item/crafting/RecipeType;"
					+ "Lnet/minecraft/world/level/block/entity/FuelValues;)I";
	static final String MONSTER_ROOM_FEATURE = "net/minecraft/world/level/levelgen/feature/MonsterRoomFeature";
	static final String MONSTER_ROOM_HOOKS = "net/neoforged/neoforge/common/MonsterRoomHooks";
	static final String RANDOM_MONSTER_ROOM_MOB =
			"(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/entity/EntityType;";
	static final String NEO_SERVER_LIFECYCLE_HOOKS = "net/neoforged/neoforge/server/ServerLifecycleHooks";
	static final String RUN_MODIFIERS = "(Lnet/minecraft/server/MinecraftServer;)V";

	static final String ICONDITION = ForeignType.ICONDITION.internal(Ecosystem.NEOFORGE);
	static final String CODEC_DESC = "Lcom/mojang/serialization/Codec;";
	static final String KERNEL_NEO_CONDITIONS = "net/forbric/kernel/runtime/KernelNeoConditions";
	static final String FORGE_ICONDITION = ForeignType.ICONDITION.internal(Ecosystem.FORGE);
	static final String KERNEL_FORGE_CONDITIONS = "net/forbric/kernel/runtime/KernelForgeConditions";
	static final String KERNEL_FORGE_RELOAD = "net/forbric/kernel/runtime/KernelForgeReload";
	static final String KERNEL_FORGE_INGREDIENTS = "net/forbric/kernel/runtime/KernelForgeIngredients";
	static final String KERNEL_FORGE_FLUIDS = "net/forbric/kernel/runtime/KernelForgeFluids";
	static final String FLUID_RENDERER = "net/minecraft/client/renderer/block/FluidRenderer";
	static final String FLUID_MODEL = "Lnet/minecraft/client/renderer/block/FluidModel;";
	static final String FLUID_STATE = "Lnet/minecraft/world/level/material/FluidState;";
	static final String TESSELATE_DESC = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;"
			+ FLUID_STATE + ")V";
	static final String FLUID_MODEL_FUNNEL_DESC = "(" + FLUID_MODEL + FLUID_STATE
			+ "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)" + FLUID_MODEL;
	static final String WEIGHTED_VARIANTS = "net/minecraft/client/renderer/block/dispatch/WeightedVariants";
	static final String BLOCK_STATE_MODEL = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
	/** NeoForge-only: MinecraftForge composes its ingredient codec in ForgeHooks, so ForeignType has no pair. */
	static final String NEO_INGREDIENT_CODECS = "net/neoforged/neoforge/common/crafting/IngredientCodecs";
	static final String CODEC_TO_CODEC = "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;";
	static final String BOOTSTRAP = "net/minecraft/server/Bootstrap";
	static final String RELOADABLE_SERVER_RESOURCES = "net/minecraft/server/ReloadableServerResources";
	static final String RELOAD_HOOK_DESC = "(L" + RELOADABLE_SERVER_RESOURCES
			+ ";Lnet/minecraft/core/RegistryAccess;Ljava/util/Map;)Ljava/util/List;";
	/** The carrier's own reload event; NeoForge's twin has a different name, so ForeignType has no pair. */
	static final String FORGE_RELOAD_EVENT = "net/minecraftforge/event/AddReloadListenerEvent";
	static final String FORGE_CONDITION_CONTEXT_DESC = "()L" + FORGE_ICONDITION + "$IContext;";
	static final String JSON_RELOAD_LISTENER = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
	static final String DATA_RESULT = "Lcom/mojang/serialization/DataResult;";

	static final String CONDITIONAL_OPS = "net/neoforged/neoforge/common/conditions/ConditionalOps";
	static final String CONDITIONAL_FACTORY =
			"(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;";
	static final String KERNEL_FABRIC_CONDITIONS = "net/forbric/kernel/runtime/KernelFabricConditions";

	static final String DEFAULT_ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/DefaultAttributes";
	static final String NEO_COMMON_HOOKS = "net/neoforged/neoforge/common/CommonHooks";
	static final String ATTRIBUTES_VIEW = "()Ljava/util/Map;";
	static final String KERNEL_FORGE_ATTRIBUTES = "net/forbric/kernel/runtime/KernelForgeAttributes";

	static final String ADD_CLIENT_RELOAD_LISTENERS =
			"net/neoforged/neoforge/client/event/AddClientReloadListenersEvent";
	static final String VANILLA_CLIENT_LISTENERS =
			"net/neoforged/neoforge/client/resources/VanillaClientListeners";
	static final String NAME_FOR_CLASS =
			"(Ljava/lang/Class;)Lnet/minecraft/resources/Identifier;";
	static final String KERNEL_RELOAD_NAMES = "net/forbric/kernel/runtime/KernelClientReloadNames";
	/** NeoForge's retyping of vanilla's {@code providers}: the one the merged {@code <init>} actually writes. */
	/**
	 * The methods measured to be merge-injected in this shape, and worth removing.
	 *
	 * <p>Derived, not guessed: every pure interface-default delegate in the merged base that shadows a real
	 * superclass method was differenced against both unmerged bases. 253 of 256 exist only after the merge, but
	 * three do not — vanilla writes the same shape on purpose — so the shape alone cannot decide. This entry is
	 * the one whose occurrences are all merge-introduced and whose bypassed method does something visible: it is
	 * what applies a team's colour and prefix to a name.
	 */
	static final java.util.Set<String> MEASURED_MERGE_STUBS =
			java.util.Set.of("getDisplayName()Lnet/minecraft/network/chat/Component;");

	/**
	 * The classes MinecraftForge rooted its capability system at, and the merge rooted at NeoForge's attachment
	 * holder instead. {@code LevelChunk} is absent on purpose: it kept both methods through the merge.
	 */
	static final java.util.Set<String> CAPABILITY_ROOTS = java.util.Set.of(
			"net/minecraft/world/entity/Entity",
			"net/minecraft/world/level/block/entity/BlockEntity",
			"net/minecraft/world/level/Level");
	/** {@code org.slf4j.Logger}, the one unwritten static the merge leaves that has an obvious correct value. */
	static final String LOGGER_DESC = "Lorg/slf4j/Logger;";
	/** {@code EntitySpawnReason}, the type of both of the merged {@code Mob}'s spawn fields. */
	static final String SPAWN_REASON = "Lnet/minecraft/world/entity/EntitySpawnReason;";
	static final String NAME_KEYED = "Ljava/util/Map;";
	/** Vanilla's own descriptor for it, and the one fabric-api reads. */
	static final String ID_KEYED = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;";
	static final String KERNEL_PARTICLES = "net/forbric/kernel/runtime/KernelParticleProviders";
	/** Old owner → the kernel class that now carries the method, for hooks the merged base still names. */
	static final Map<String, String> LEGACY_INTEROP_OWNERS = Map.of(
			"net/forbric/loader/impl/compat/ForbricCustomPayloadInterop", "net/forbric/kernel/interop/PayloadInterop",
			"net/forbric/loader/impl/forge/runtime/ForbricClientShutdown", "net/forbric/kernel/interop/ClientShutdown",
			"net/forbric/loader/impl/forge/runtime/ForbricForgeRuntimeInterop",
			"net/forbric/kernel/interop/ForgeRuntimeInterop");

	static final String XOROSHIRO_RANDOM_SOURCE = "net/minecraft/world/level/levelgen/XoroshiroRandomSource";
	static final String BIT_RANDOM_SOURCE = "net/minecraft/world/level/levelgen/BitRandomSource";
	/** 2^-53: the multiplier that turns 53 random bits into a double in [0,1). Exactly representable in both widths. */
	static final float DOUBLE_UNIT_AS_FLOAT = (float) 0x1.0p-53;
	static final double DOUBLE_UNIT = 0x1.0p-53;
	/**
	 * Switches the repair off, which puts the game back on the float-rounded draw.
	 *
	 * <p>It exists so gate-m31 can demonstrate its own teeth: a parity gate that has never been seen to go red is
	 * not evidence that the worlds match, only that the comparison ran. With this off, the gate's biome check
	 * must fail.
	 */
	static final String RANDOM_PRECISION_PROPERTY = "forbric.randomSourcePrecision";

	static boolean randomSourcePrecisionEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(RANDOM_PRECISION_PROPERTY, "on"));
	}

	static final double HALF_TURN_IN_DEGREES = 180.0;
	/** {@code (double)(float)Math.PI} — what the decompiler wrote where vanilla's source said {@code (float)Math.PI}. */
	static final double PI_AS_FLOAT = (double) (float) Math.PI;
	/** Vanilla's own constant: the same expression folded in FLOAT at compile time, then widened. */
	static final double RADIANS_TO_DEGREES = (double) (float) (180.0F / (float) Math.PI);

	static final String CHUNK_STATUS = "net/minecraft/world/level/chunk/status/ChunkStatus";
	static final String CHUNK_SAVE_HEIGHTMAPS = "chunkSaveHeightmaps";
	static final String HEIGHTMAPS_AFTER = "heightmapsAfter";
	static final String ENUM_SET_DESC = "Ljava/util/EnumSet;";
	static final String SAVED_HEIGHTMAPS_PROPERTY = "forbric.vanillaSavedHeightmaps";

	static boolean savedHeightmapsEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SAVED_HEIGHTMAPS_PROPERTY, "on"));
	}

	/**
	 * Removes GUI overrides whose whole body is {@code SomeInterface.super.sameMethod(args)}.
	 *
	 * <p>The byte-merge gives many client GUI classes an {@code implements ContainerEventHandler} they do not have in
	 * vanilla, plus a {@code keyPressed(KeyEvent)} override that does nothing but call the INTERFACE DEFAULT. On a
	 * plain widget that is harmless — nothing in its superclass chain declares {@code keyPressed}, so the default
	 * applies either way. On a {@code Screen} subclass it is a silent functional break: a class method beats an
	 * interface default, so the injected override SHADOWS {@code Screen.keyPressed} — and {@code Screen.keyPressed}
	 * is the only place the {@code isEscape() -> shouldCloseOnEsc() -> onClose()} branch lives.
	 *
	 * <p>Symptom: ESC cannot close the pause menu (or the options/world-selection screens), while ESC still closes
	 * the inventory, because {@code AbstractContainerScreen} carries its own ESC handling. Verified against the
	 * unmerged 26.2 client: vanilla {@code PauseScreen} is {@code extends Screen} with NO {@code keyPressed} override
	 * and no {@code ContainerEventHandler}, so the override is purely a merge artifact.
	 *
	 * <p>Deleting it is safe in BOTH shapes for THIS method, which is why this needs no class-hierarchy walk: for a
	 * {@code Screen} subclass the inherited {@code Screen.keyPressed} takes over (exactly vanilla dispatch), and for
	 * a widget with no superclass declaration the interface default still resolves — the same method that was being
	 * called explicitly. {@code ContainerEventHandler} extends {@code GuiEventListener}, so the two {@code keyPressed}
	 * defaults are ordered by specificity and deleting the override cannot create an ambiguity.
	 *
	 * <p><b>Restricted to methods {@code ContainerEventHandler} itself refines, and that restriction is
	 * load-bearing.</b> The same "body is only {@code Iface.super.same()}" shape ALSO expresses Java's mandatory
	 * diamond disambiguation: when two UNRELATED interfaces each supply the default, the class must override to pick
	 * one, and deleting that is not a no-op but unresolvable. Generalising by shape alone removed
	 * {@code getRectangle} — supplied by both {@code LayoutElement} and {@code GuiEventListener} — and every GUI
	 * screen died at the title screen on {@code IncompatibleClassChangeError: Conflicting default methods}.
	 *
	 * <p>{@link #SHADOWABLE} is exactly the set where that cannot happen: each entry is declared {@code default} by
	 * BOTH {@code ContainerEventHandler} and {@code GuiEventListener}, and since
	 * {@code ContainerEventHandler extends GuiEventListener} its version is strictly more specific, so removing an
	 * override always resolves to one winner. Verified against the merged jar: no other interface anywhere in
	 * {@code net/minecraft/client/gui/} declares any of them — whereas {@code getRectangle}, the one that broke, is
	 * NOT refined by {@code ContainerEventHandler} and so is correctly excluded by this rule.
	 *
	 * <p>Why the whole set and not just the one method that was reported: the merge injects these blindly, and each
	 * one silently shadows whatever real implementation the superclass chain had. {@code keyPressed} cost ESC on the
	 * pause menu; {@code mouseScrolled} cost ALL list scrolling ({@code AbstractContainerWidget} shadowed
	 * {@code AbstractScrollArea}'s real wheel handling, and {@code AbstractSelectionList} sits under it, so every
	 * scrollable list — mod list, world list, options — was dead); the click/drag/char entries are the same latent
	 * bug on paths nobody has exercised yet. Removing a delegate whose superclass chain has no real implementation
	 * is a no-op, so applying this to the whole set costs nothing and closes the rest of the family.
	 */
	static final String CONTAINER_EVENT_HANDLER =
			"net/minecraft/client/gui/components/events/ContainerEventHandler";

	/** name+descriptor of every {@code ContainerEventHandler} default that also refines a {@code GuiEventListener} one. */
	static final java.util.Set<String> SHADOWABLE = java.util.Set.of(
			"keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
			"keyReleased(Lnet/minecraft/client/input/KeyEvent;)Z",
			"charTyped(Lnet/minecraft/client/input/CharacterEvent;)Z",
			"preeditUpdated(Lnet/minecraft/client/input/PreeditEvent;)Z",
			"mouseScrolled(DDDD)Z",
			"mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
			"mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z",
			"mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z");

	static final String STACK_COUNT_MESSAGE = "The stack count must be 1";

	static final String FORGE_MODEL_DATA_MANAGER = "net/minecraftforge/client/model/data/ModelDataManager";
	static final String FORGE_MODEL_DATA = "net/minecraftforge/client/model/data/ModelData";
	/** Forge-only, like {@link #FORGE_MODEL_DATA}: NeoForge has no INBTBuilder, so ForeignType has no pair for it. */
	static final String FORGE_NBT_BUILDER = "net/minecraftforge/common/util/INBTBuilder$Builder";
	static final String NBT_BUILDER_FACTORY_DESC = "()L" + FORGE_NBT_BUILDER + ";";

	/**
	 * Takes the loader brand out of the window title.
	 *
	 * <p>{@code Minecraft.createTitle} builds "Minecraft" and then, when the game reports itself as modified, splices
	 * in a space, the loader's name and an asterisk before the version — so the merged base, whose title patch is
	 * NeoForge's, puts "NeoForge" on the window of an instance that is running Fabric, MinecraftForge and NeoForge
	 * mods side by side. Naming one of the three is worse than naming none.
	 *
	 * <p>The brand and its leading space go; the asterisk stays, which is vanilla's own mark for a modified game and
	 * leaves the title reading "Minecraft* 26.2". Only that one append chain is touched, so a title patch that
	 * changes shape is left alone rather than half-rewritten.
	 */
	static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
	static final String FORGE_EVENT_FACTORY = "net/minecraftforge/event/ForgeEventFactory";
	static final String ON_ITEM_TOOLTIP = "onItemTooltip";
	static final String TOOLTIP_BRIDGE = "net/forbric/kernel/runtime/KernelItemTooltips";
	static final String TOOLTIP_BRIDGE_DESC =
			"(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;Ljava/util/List;"
					+ "Lnet/minecraft/world/item/TooltipFlag;Lnet/minecraft/world/item/Item$TooltipContext;"
					+ "Lnet/minecraft/world/item/component/TooltipDisplay;)V";

	static final String ATTRIBUTE_MODIFIERS_TYPE = "net/minecraft/world/item/component/ItemAttributeModifiers";
	static final String DATA_COMPONENTS = "net/minecraft/core/component/DataComponents";
	static final String NEO_ATTRIBUTES = "getAttributeModifiers";

	/** The next instruction that is not a label, line number or frame. */
	// ---------------------------------------------------------------------------------------------------------------
	// The legacy global_loot_modifiers.json index, seen by two managers with two ideas of what it is
	// ---------------------------------------------------------------------------------------------------------------

	static final String LOOT_MODIFIER_MANAGER_FORGE = ForeignType.LOOT_MODIFIER_MANAGER.internal(Ecosystem.FORGE);
	static final String LOOT_MODIFIER_MANAGER_NEO = ForeignType.LOOT_MODIFIER_MANAGER.internal(Ecosystem.NEOFORGE);
	static final String SIMPLE_JSON_LISTENER = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
	static final String PREPARE = "prepare";
	static final String PREPARE_DESC = "(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)Ljava/util/Map;";
	static final String KERNEL_LOOT_MODIFIERS = "net/forbric/kernel/runtime/KernelLootModifiers";
	static final String FEATURE_FLAGS = "net/minecraft/world/flag/FeatureFlags";
	static final String NEO_FEATURE_FLAG_LOADER = "net/neoforged/neoforge/common/util/flag/FeatureFlagLoader";
	static final String KERNEL_FEATURE_FLAGS = "net/forbric/kernel/runtime/KernelFeatureFlags";
	static final String LOAD_MODDED_FLAGS = "loadModdedFlags";
	static final String LOAD_MODDED_FLAGS_DESC = "(Lnet/minecraft/world/flag/FeatureFlagRegistry$Builder;)V";
	static final String SPRITE_LOADER = "net/minecraft/client/renderer/texture/SpriteLoader";
	static final String FORGE_CLIENT_CONFIG = "net/minecraftforge/common/ForgeConfig$Client";
	static final String MIPMAP_LOWERING = "allowMipmapLowering";
	/** {@code -Dforbric.mipmapLowering=off} hands the decision back to MinecraftForge's config (and its false default). */
	static final String MIPMAP_PROPERTY = "forbric.mipmapLowering";

	static boolean mipmapLoweringEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(MIPMAP_PROPERTY, "on"));
	}

	static final String INPUT_CONSTANTS = "com/mojang/blaze3d/platform/InputConstants";
	/** {@code -Dforbric.keyModifierSuffix=off} hands the whole value back to vanilla's parse, which throws on it. */
	static final String KEY_SUFFIX_PROPERTY = "forbric.keyModifierSuffix";

	static boolean keyModifierSuffixEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(KEY_SUFFIX_PROPERTY, "on"));
	}

	static final String WITHOUT_INDEX = "withoutTheLegacyIndex";
	static final String WITHOUT_INDEX_DESC = "(Lnet/minecraft/server/packs/resources/ResourceManager;)Lnet/minecraft/server/packs/resources/ResourceManager;";

	// ---------------------------------------------------------------------------------------------------------------
	// A pack.mcmeta overlay gated by a condition no evaluator here can judge
	// ---------------------------------------------------------------------------------------------------------------

	static final String OVERLAY_ENTRY = "net/minecraft/server/packs/OverlayMetadataSection$OverlayEntry";
	static final String LIST_CODEC_FOR_PACK_TYPE = "listCodecForPackType";
	static final String LIST_CODEC_DESC = "(Lnet/minecraft/server/packs/PackType;)Lcom/mojang/serialization/Codec;";
	static final String CONDITIONAL_OPS_NEO = "net/neoforged/neoforge/common/conditions/ConditionalOps";
	static final String DECODE_LIST_WITH_CONDITIONS = "decodeListWithElementConditions";
	static final String KERNEL_NEO_CONDITIONS_CLASS = "net/forbric/kernel/runtime/KernelNeoConditions";

	// ---------------------------------------------------------------------------------------------------------------
	// A javac switch map whose synthetic holder class the merge replaced
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * One {@code switch} over an enum whose javac-generated {@code $SwitchMap$} holder class lost the merge.
	 *
	 * @param user     the class whose method switches
	 * @param holder   the synthetic inner class javac put the map in ({@code Owner$N})
	 * @param field    the map field ({@code $SwitchMap$<enum with $ for .>})
	 * @param enumType the enum switched over
	 * @param cases    case index (the value the map stored, 1-based) → enum constant name
	 */
	record LostSwitchMap(String user, String holder, String field, String enumType, Map<Integer, String> cases) {
	}

	/**
	 * javac compiles {@code switch (direction)} through a synthetic {@code Owner$N} class holding
	 * {@code static final int[] $SwitchMap$…}, numbered with the other anonymous classes of {@code Owner}. Both
	 * families patch {@code AbstractFurnaceBlockEntity}: MinecraftForge's {@code $2} is the switch map its
	 * {@code getCapability} needs, NeoForge's {@code $2} is a {@code SnapshotJournal} — and the merge kept ONE
	 * class per name. MinecraftForge's body then reads a field NeoForge's class never had, and every Forge pipe
	 * or hopper asking a furnace for {@code ITEM_HANDLER} dies with {@code NoSuchFieldError: $SwitchMap$…}.
	 * Found by the E7 furnace probe on gate-m29; a census of the whole base (in the test) finds exactly this one.
	 */
	static final List<LostSwitchMap> LOST_SWITCH_MAPS = List.of(new LostSwitchMap(
			"net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity",
			"net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity$2",
			"$SwitchMap$net$minecraft$core$Direction",
			"net/minecraft/core/Direction",
			Map.of(1, "UP", 2, "DOWN")));

	static final String INTEGRATED_SERVER = "net/minecraft/client/server/IntegratedServer";
	static final String TEARDOWN_PUBLISHED_STATE = "teardownPublishedState";
	static final String FORBRIC_LOG = "net/forbric/kernel/util/ForbricLog";

	static final java.util.Set<Object> LOADER_BRANDS = java.util.Set.of("NeoForge", "Forge", "Fabric");

	static final String GUI_RENDERER = "net/minecraft/client/gui/render/GuiRenderer";
	static final String PIP_RENDERERS = "pictureInPictureRenderers";
	static final String PIP_POOLS = "pictureInPictureRendererPools";
	static final String PIP_PREPARE = "preparePictureInPictureState";
	static final String PIP_BUILDER_OWNER = "net/forbric/kernel/runtime/KernelForgePipRenderers";
	static final String PIP_BRIDGE = "forbric$prepareOrphanedPip";


	static boolean stillNamesTheOldLoader(byte[] classBytes) {
		return MergedBaseLegacyNames.stillNamesTheOldLoader(classBytes);
	}

	static boolean restoreForgeClientInit(ClassNode node) {
		return MergedBaseClientRepair.restoreForgeClientInit(node);
	}

	static boolean restoreForgeGeometryReload(ClassNode node) {
		return MergedBaseClientRepair.restoreForgeGeometryReload(node);
	}


}
