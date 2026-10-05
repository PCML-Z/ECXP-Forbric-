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

package net.forbric.kernel.config;

/**
 * The shipped sample declaration, written to {@code <gameDir>/forbric/forbric.toml.example} and kept in the jar.
 *
 * <p>It is written from one string rather than being a resource file for two reasons. A declaration only helps if the
 * person editing it can see every key in one place with its reason, and that means comments — which a resource copied
 * verbatim from a documentation page loses. And the sample is the reference for what this Forbric understands, so it
 * is generated from the same list the reader validates against
 * ({@link ForbricConfigTest#everyKnownKeyParsesAsAFileWouldWriteIt} fails when the two drift), which a hand-maintained
 * .txt cannot be.
 *
 * <p>Everything here is at its DEFAULT value. A file that changes nothing is the safest thing a pack author can ship,
 * and it doubles as the diff that shows which keys this Forbric reads.
 */
final class ForbricConfigExample {

	private ForbricConfigExample() {
	}

	/** The sample, as it is written to disk. */
	static String toToml() {
		return """
				# Forbric — instance declaration
				#
				# Read by the LOADER, about the LOADER, and written by whoever builds this instance.
				# Every other declaration in a Forbric instance points the other way: a mod's mods.toml or
				# fabric.mod.json says what that MOD needs, and is written by that mod's author. This one says how
				# the loader should behave, which is why it lives here and not inside any jar.
				#
				# Precedence: -D system property  >  this file  >  the built-in default.
				# A system property is a deliberate act at one launch, so it wins. This file is the standing
				# configuration; the default is what Forbric does unconfigured.
				#
				# This file cannot move a measured value. The Minecraft generation and the anchor tables are fixed
				# when Forbric is built and cross-checked against every artifact they name; setting one here is
				# refused with a warning, because a file that silently ignored a key reads as a file that works.
				#
				# Everything below is at its default. An instance that needs nothing may delete this file whole.

				[ecosystem]
				# Which family a multi-loader jar belongs to when it declares several. A single-family jar is always
				# owned by the family it declares, and this never applies to it.
				# Default: "neoforge,minecraftforge,fabric"
				preference = "neoforge,minecraftforge,fabric"

				# When two jars carry the SAME mod id — inevitable once a Fabric pack and a NeoForge pack meet — which
				# one wins. The other is suppressed and the reason is logged once.
				# dupeIdPreference = ""
				# nestedDupePreference = ""

				[bridges]
				# The two Forge families' hooks compete for the same call sites on the merged base, and one wins
				# each. The loser's site is dead code, so its events are re-emitted to the other family — the bridge.
				# "off" installs none, which is the pre-bridge behaviour: each family sees only what its own hook won.
				# Every bridged event that fails to install is reported with what a player then loses.
				unified = "on"

				[diagnostics]
				# "strict" makes the mixer refuse an anchor it cannot place instead of dropping the injector.
				# mixinFit = "default"

				# How a pack.mcmeta section this instance cannot parse is read: "on" treats a namespaced section as
				# absent (what a single-loader instance does), "all" extends that to vanilla's own section names, "off"
				# restores everything-throws.
				packMetadataFailSoft = "on"

				# What happens when a mod did not finish loading.
				#
				# A mod counts as a problem both when its constructor or entrypoint threw (FAILED — its container is
				# withdrawn) AND when only part of it did not run (DEGRADED — a setup phase threw, a mixin was
				# suppressed, a field it reads drifted, a capability it declares nobody implements). Both require a
				# decision, and on a client that means the launch stops and asks.
				#
				# "ask" is the default and opens that window. "continue" launches anyway and records the loss in
				# .forbric-kernel/load-report.txt. "strict" stops with nobody asked.
				#
				# Set this to "continue" for an instance you have checked. A partial loss is deliberately
				# over-reported — a mod's optional dependency may be absent on purpose, and a mixin the mod's own
				# plugin declined is not a fault at all — so an instance that used to start can stop here, and some of
				# those stops will be false. That is the trade for a player being able to act on it instead of
				# wondering why a mod does nothing.
				compatibilityPolicy = "ask"

				[mixin]
				# guest mixin configs, whole, to relax. A guest injector whose anchor the merge moved is a soft skip
				# rather than a fatal MixinApplyError.
				relaxGuest = "on"

				# Configs to suppress or keep, by name or mod id. Both accept a comma-separated list.
				# suppress = ""
				# keep = ""
				# disableConfigs = ""
				# enableConfigs = ""
				# relaxOverwrites = ""

				[runtime]
				# Each of these is a feature that can be turned off when it is the thing misbehaving. They are on by
				# default; an instance turns one off after observing it, and says so here.
				forgeClientInit = "on"
				# forgeWorldgen = "on"
				# forgeHandshake = "on"
				# forgeInternalSubscribers = "on"
				# commonNetworkInterop = "on"
				# transferBridge = "on"
				# chunkExecutorGuard = "on"
				# hopperFabricStorage = "on"
				# earlyConfigs = "on"
				# neoTooltipAppenders = "on"
				# neoRegistrationOrder = "on"
				# publishModList = "on"
				# loaderProbes = "on"
				""";
	}
}
