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

package net.forbric.installer.kernel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The upstream versions each supported Minecraft generation builds against, keyed by that generation's Minecraft
 * version.
 *
 * <p>They are pins, not defaults: each one was chosen because a specific thing breaks at the neighbouring
 * versions, and the reason lives next to the number so nobody "updates" it back into the failure. Everything the
 * install produces is keyed on this set, so bumping any of them invalidates the cached artifacts that depend on
 * it — {@link BuildStamp} folds the whole set into one string per version, so two generations can never share a
 * cache entry.
 *
 * <p><b>Generations, not a range.</b> Forbric is NOT version-agnostic: a generation is one Minecraft version whose
 * namespace strategy, Forge/NeoForge toolchain and bytecode anchors are all measured against that exact build.
 * Two generations cannot be served by the same merged base, so they are modelled here as independent entries
 * rather than as a lower/upper bound. The namespace split is the sharp edge:
 *
 * <ul>
 *   <li><b>26.2</b> is Mojmap-native — the vanilla jar is already deobfuscated — so the canonical runtime
 *       namespace is <em>identity</em> ({@code -Dforbric.runtimeNamespace=named}): no intermediary, no remap.</li>
 *   <li><b>1.21.1 / 1.21.8</b> are obfuscated, so the canonical runtime namespace is <em>intermediary</em> and a
 *       Forge mod's Mojmap bytecode is remapped into it (the strategy 1.21.11 proved). Both still ship a NeoForm
 *       userdev config — NeoForge covers every 1.21.x release on its own build counter — so both use the same
 *       three-carrier merged base and the same NeoFormRuntime path as 26.2. What does not carry over is anything
 *       measured: the anchor tables and the transfer shape audit are 26.2's.</li>
 * </ul>
 *
 * <p>Adding a generation therefore means adding an entry here AND regenerating that version's anchor tables
 * (see {@code docs/ multi-version plan}); the pin is the declaration, the anchors are the reality.
 */
final class Pins {

	private Pins() {
	}

	/** One Minecraft generation's pins. Immutable; {@link #stamp()} is its cache key contribution. */
	record PinSet(String minecraft, String forge, String neoforge, String nfrt, String nfrtResult) {

		/**
		 * The NeoForge artifact NeoFormRuntime is pointed at. The bare coordinate does not exist on the Maven.
		 * Empty {@code neoforge} (a generation with no NeoForge, e.g. 1.21.1) means there is no such coordinate.
		 */
		String neoforgeUserdevCoordinate() {
			return neoforge.isEmpty() ? "" : "net.neoforged:neoforge:" + neoforge + ":userdev";
		}

		/** NeoFormRuntime's own fat jar. Empty for a generation that does not use NFRT. */
		String nfrtCoordinate() {
			return nfrt.isEmpty() ? "" : "net.neoforged:neoform-runtime:" + nfrt + ":all";
		}

		/** Whether this generation has a NeoForge carrier at all. */
		boolean hasNeoForge() {
			return !neoforge.isEmpty();
		}

		/** A one-line summary for the build stamp, so a cached artifact records what produced it. */
		String stamp() {
			return "mc=" + minecraft + " forge=" + forge + " neoforge=" + neoforge
					+ " nfrt=" + nfrt + " result=" + nfrtResult;
		}
	}

	/**
	 * The default generation, and the one the CLI falls back to when no {@code --mc} is given. 26.2 is the
	 * Mojmap-native generation every gate currently runs against.
	 */
	static final String DEFAULT_MINECRAFT = "26.2";

	// ---- 26.2 (Mojmap-native, identity namespace, three carriers) ------------------------------------------------

	/**
	 * Minecraft 26.2: the current default.
	 *
	 * <p>Mojmap-native, so the canonical runtime namespace is identity and the merged base is NeoForge's game
	 * with MinecraftForge's patches merged in (three carriers: merged base + forge-runtime + neoforge-runtime).
	 */
	private static final PinSet V26_2 = new PinSet(
			"26.2",
			// MinecraftForge, in its own <mc>-<fml> coordinate form.
			"26.2-65.0.1",
			// NeoForge, on the first release line rather than a beta. Re-measured at the bump: .38-beta → .88
			// removes 12 classes and adds 24; of the 98 jars in the reference pack exactly two named anything
			// removed. .57 and up are releases, and a beta carrier tells every player it is a beta.
			"26.2.0.88",
			// NeoFormRuntime, pinned to the build whose gameJar result was checked byte-for-byte against the
			// reference (sha1 5b2970209ee12702117309576b08521aa38ae67b).
			"2.0.18",
			// gameJarNoRecomp is the binary-patch path: same 10,963 classes as the recompile path in ~6s, with
			// no decompiler / 4 GB heap / javac, and an identical conflict report as a set.
			"gameJarNoRecomp");

	// ---- 1.21.8 (obfuscated, intermediary namespace, three carriers) ---------------------------------------------

	/**
	 * Minecraft 1.21.8: obfuscated, so the canonical runtime namespace is intermediary (the 1.21.11 strategy).
	 *
	 * <p>NeoForge 21.8.54 ships a NeoForm userdev config just like 26.2's does — its {@code config.json} names
	 * {@code net.neoforged:neoform:1.21.8-20250717.133445} and a binarypatcher — so the three-carrier merged base
	 * and the NeoFormRuntime path both apply unchanged. What does NOT carry over is every measured artifact: the
	 * anchor tables and the transfer shape audit are 26.2's, and this generation has to be re-measured before any
	 * of it can be claimed as working.
	 */
	private static final PinSet V1_21_8 = new PinSet(
			"1.21.8",
			// MinecraftForge, <mc>-<fml> form. Read off the Forge Maven's own metadata: the 1.21.8 line is
			// 58.1.x, and 58.1.22 is its current head. (There is no 54.x on 1.21.8 — that is the 1.21.4 line.)
			"1.21.8-58.1.22",
			// NeoForge: the 21.8.x release line; 21.8.54 is its head, and its userdev names neoform 1.21.8-.
			"21.8.54",
			// The same NeoFormRuntime line as 26.2: 2.0.18 was validated against 26.2's bytes, and NFRT reads
			// the neoform coordinate out of the NeoForge userdev config rather than carrying one per MC version,
			// so the same build serves both. Re-pin if a byte-for-byte check against 1.21.8 says otherwise.
			"2.0.18",
			"gameJarNoRecomp");

	// ---- 1.21.1 (obfuscated, intermediary namespace, three carriers) ---------------------------------------------

	/**
	 * Minecraft 1.21.1: obfuscated, intermediary namespace, three carriers.
	 *
	 * <p>NeoForge <b>does</b> exist for 1.21.1 — 21.1.255, whose userdev {@code config.json} names
	 * {@code net.neoforged:neoform:1.21.1-20240808.144430}. The NeoForge fork of MinecraftForge happened at
	 * 1.20.5, which is where NeoForge's own line <em>starts</em>, not where it stops: every 1.21.x release has
	 * one, on its own build counter (21.1.x, 21.2.x, 21.4.x, 21.8.x …). So this generation uses the same
	 * three-carrier merged base as the other two rather than needing a two-carrier degradation.
	 */
	private static final PinSet V1_21_1 = new PinSet(
			"1.21.1",
			// MinecraftForge, <mc>-<fml> form. From the Forge Maven metadata: the 1.21.1 line is 52.1.x and
			// 52.1.16 is its head. (52.0.x does not exist.)
			"1.21.1-52.1.16",
			// NeoForge 21.1.255, verified against its own userdev config to target Minecraft 1.21.1.
			"21.1.255",
			// Same NFRT line as 26.2; see V1_21_8.
			"2.0.18",
			"gameJarNoRecomp");

	/** Every supported generation, in insertion order. The map key is the Minecraft version. */
	private static final Map<String, PinSet> BY_MINECRAFT = new LinkedHashMap<>();

	static {
		BY_MINECRAFT.put(V26_2.minecraft(), V26_2);
		BY_MINECRAFT.put(V1_21_8.minecraft(), V1_21_8);
		BY_MINECRAFT.put(V1_21_1.minecraft(), V1_21_1);
	}

	/** The Minecraft versions this installer can build, in the order the CLI lists them. */
	static Set<String> supportedMinecraftVersions() {
		return BY_MINECRAFT.keySet();
	}

	/** Whether {@code minecraft} has a pin set here. */
	static boolean isSupported(String minecraft) {
		return BY_MINECRAFT.containsKey(minecraft);
	}

	/**
	 * The pins for {@code minecraft}.
	 *
	 * @throws IllegalArgumentException if the version has no pin set — the installer must never silently fall back
	 *                                  to 26.2, because that builds a carrier for the wrong Minecraft.
	 */
	static PinSet forVersion(String minecraft) {
		PinSet pins = BY_MINECRAFT.get(minecraft);
		if (pins == null) {
			throw new IllegalArgumentException("no pins for Minecraft " + minecraft
					+ "; this installer supports " + String.join(", ", BY_MINECRAFT.keySet()));
		}
		return pins;
	}

	// ---- Default-generation shortcuts (the CLI's --mc default) ---------------------------------------------------

	/** The default generation's Minecraft version (26.2). */
	static String minecraft() {
		return DEFAULT_MINECRAFT;
	}

	/** The default generation's MinecraftForge pin. */
	static String forge() {
		return V26_2.forge();
	}

	/** The default generation's NeoForge pin. */
	static String neoforge() {
		return V26_2.neoforge();
	}

	/** A one-line summary of the default generation, for the build stamp. */
	static String stamp() {
		return V26_2.stamp();
	}
}
