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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;

import net.forbric.kernel.util.ForbricLog;

/**
 * The instance's own declaration file — {@code <gameDir>/forbric/forbric.toml} — read by the loader, about the
 * loader, written by whoever builds the instance.
 *
 * <p><b>Direction of authority.</b> Every other declaration in a Forbric instance points the other way: a mod's
 * {@code mods.toml}, {@code fabric.mod.json} or {@code pack.mcmeta} says what that MOD needs and is written by that
 * mod's author. This one says what the LOADER should do and is written by the pack author or server operator, which
 * is why it lives in the game directory rather than inside any jar. Nothing in it can make a mod load, and nothing in
 * a mod can change it.
 *
 * <p>It exists because every one of these switches was otherwise reachable only as a {@code -D} system property, so
 * the documented advice for a degraded instance was "edit the launch command". That is advice a player on a
 * multiplayer server cannot follow and an operator should not have to: the decision belongs in the instance, next to
 * the mods it is about, where it is reviewable in a diff.
 *
 * <p><b>Precedence: {@code -D} &gt; this file &gt; the built-in default.</b> A system property is a deliberate act at
 * one launch, so it wins; the file is the standing configuration; the default is what Forbric does unconfigured. Each
 * resolved value can say which of the three it came from, so a report never leaves the reader guessing whether a
 * setting is theirs or the project's.
 *
 * <h2>What this file may NOT do</h2>
 *
 * <p>A file a pack author can edit is a file that can be edited into a state nobody measured. So the version pins and
 * the measured anchor tables are deliberately <b>not</b> reachable from here:
 *
 * <ul>
 *   <li>the Minecraft/Forge/NeoForge generation — chosen at build time and cross-checked against every downloaded
 *       coordinate ({@code Pins}); a config that moved it would install a carrier for one game under a profile
 *       claiming another, which is the exact silent mismatch the stamp exists to prevent;</li>
 *   <li>the anchor tables and transfer shape fingerprints — measured against one build's bytecode
 *       ({@code native-only-methods.txt}, {@code ForgeTransferShapeAudit}); a config that picked a different one
 *       would produce a confidently wrong answer rather than a missing one.</li>
 * </ul>
 *
 * <p>An attempt is refused loudly by {@link #rejectReserved} rather than ignored, because a pack author who wrote
 * {@code minecraft = "1.21.1"} and watched it be silently dropped would reasonably conclude the key works.
 *
 * <p>Parsing is fail-soft in the direction that keeps the game playable: an unreadable or malformed file is reported
 * and every setting falls back to its default, because a typo in an optional file must not cost a player their
 * world. An unknown key is a warning, not an error — this file grows, and a newer one read by an older Forbric should
 * still work.
 */
public final class ForbricConfig {

	/** Where the declaration lives, relative to the game directory. */
	public static final String RELATIVE_PATH = "forbric/forbric.toml";

	/**
	 * Keys that exist to hold a measured value and must never be settable from an instance file. Matched case
	 * insensitively against every key in the file, so {@code Minecraft} and {@code minecraft} are both refused.
	 */
	private static final Set<String> RESERVED = Set.of(
			"minecraft", "mcversion", "forge", "neoforge", "nfrt", "nfrtresult",
			"anchors", "anchor", "anchortable", "anchortables",
			"nativeabsenttable", "transferaudited", "pin", "pins", "stamp", "stamps");

	private static final ForbricConfig DEFAULTS = new ForbricConfig(null, null);

	private final UnmodifiableConfig root;
	private final Path source;

	private ForbricConfig(UnmodifiableConfig root, Path source) {
		this.root = root;
		this.source = source;
	}

	/** The built-in defaults, for a game directory with no declaration file. */
	public static ForbricConfig defaults() {
		return DEFAULTS;
	}

	/**
	 * Every key path this Forbric understands. Published as a constant rather than kept next to the readers so that
	 * a switch that gains a file key has to be added here, where the diff shows a reader being taught to answer.
	 */
	public static final Set<String> KNOWN_KEYS = Set.of(
			// bridging
			"bridges.unified", "bridges.targeted", "bridges.reportMissing",
			// ecosystem arbitration
			"ecosystem.primary", "ecosystem.preference", "ecosystem.respectModExclusions",
			// diagnostics
			"diagnostics.eventChainAudit", "diagnostics.mixinFit", "diagnostics.packMetadataFailSoft",
			"diagnostics.deadEventAudit", "diagnostics.compatibilityPolicy",
			// mixin policy
			"mixin.relaxGuest", "mixin.suppress", "mixin.keep", "mixin.forgeFamily", "mixin.mergedBaseCompat",
			// runtime features
			"runtime.forgeClientInit", "runtime.forgeWorldgen", "runtime.transferBridge", "runtime.chunkExecutorGuard",
			"runtime.commonNetworkInterop", "runtime.forgeHandshake", "runtime.earlyConfigs",
			"runtime.neoTooltipAppenders", "runtime.neoRegistrationOrder", "runtime.hopperFabricStorage",
			"runtime.soundRegistryIdentity", "runtime.playPayloadFallThrough", "runtime.loaderProbes",
			"runtime.publishModList", "runtime.transferShapeDump", "runtime.nativeAbsentTable");

	private static volatile ForbricConfig active = DEFAULTS;

	/**
	 * Reads the instance's declaration and makes it the one every switch consults.
	 *
	 * <p>Idempotent, and a second call with the same directory is free: the transformer layer reads switches
	 * before boot reaches its own logging, so {@link #get()} has to answer correctly even if this was never
	 * called.
	 */
	public static void activate(Path gameDir) {
		ForbricConfig loaded = load(gameDir);
		active = loaded;
		writeExample(gameDir, loaded);
		if (loaded.present()) {
			ForbricLog.info("[Forbric/Config] %s — %d setting(s) declared: %s", loaded.source().getFileName(),
					loaded.declared().size(), String.join(", ", loaded.declared()));
		}
	}

	/**
	 * Drops the sample beside the declaration when there is no declaration yet, so the file an author needs to write
	 * is already there. Never overwrites: a hand-written file is the author's work, and a sample that replaced it on
	 * every launch would be a declaration that resets itself.
	 */
	private static void writeExample(Path gameDir, ForbricConfig loaded) {
		if (gameDir == null || loaded.present()) return;
		try {
			Path sample = gameDir.resolve(ForbricConfig.RELATIVE_PATH + ".example");
			if (Files.exists(sample)) return;
			Path directory = sample.getParent();
			if (directory != null) Files.createDirectories(directory);
			Files.writeString(sample, ForbricConfigExample.toToml(), java.nio.charset.StandardCharsets.UTF_8);
			ForbricLog.info("[Forbric/Config] wrote %s — every setting at its default, and what each one decides",
					sample.getFileName());
		} catch (Exception unwritable) {
			// A read-only game directory is a normal thing; the file is a convenience, not a requirement.
			ForbricLog.debug("[Forbric/Config] could not write the sample declaration: %s", String.valueOf(unwritable));
		}
	}

	/** The active declaration. Never null; the defaults before {@link #activate} and after a failed read. */
	public static ForbricConfig get() {
		return active;
	}

	/** Restores the defaults. For tests, which load different files in one JVM. */
	public static void reset() {
		active = DEFAULTS;
	}

	/**
	 * Reads the instance's declaration from {@code gameDir}, or returns the defaults when there is none.
	 *
	 * <p>Never throws. A missing file is the normal case and is silent; an unreadable or malformed one is reported
	 * once and also yields the defaults.
	 */
	public static ForbricConfig load(Path gameDir) {
		if (gameDir == null) return DEFAULTS;
		Path file = gameDir.resolve(RELATIVE_PATH);
		if (!Files.isRegularFile(file)) return DEFAULTS;
		try (java.io.Reader reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
			return of(new TomlParser().parse(reader), file);
		} catch (Exception malformed) {
			// Fail soft toward playable: the file is optional, so a typo in it must not cost a world.
			ForbricLog.warn("[Forbric/Config] could not read %s (%s); continuing with the built-in defaults",
					file.getFileName(), String.valueOf(malformed.getMessage()));
			return DEFAULTS;
		}
	}

	/** Parses from a string. For tests, and for an instance that wants to ship its declaration inline. */
	public static ForbricConfig parse(String toml) {
		try {
			return of(new TomlParser().parse(new java.io.StringReader(toml)), null);
		} catch (Exception malformed) {
			ForbricLog.warn("[Forbric/Config] could not parse the inline declaration (%s); using the built-in defaults",
					String.valueOf(malformed.getMessage()));
			return DEFAULTS;
		}
	}

	/** The shared tail: check the reserved keys, note the unknowns, wrap. */
	private static ForbricConfig of(UnmodifiableConfig parsed, Path file) {
		List<String> reserved = rejectReserved(parsed);
		if (!reserved.isEmpty()) {
			// Named one line, then carried on: the rest of the file is still the author's and mostly valid.
			ForbricLog.error("[Forbric/Config] %s sets %s — refused. That value is measured, not chosen: a version "
					+ "pin or an anchor table is fixed when Forbric is built, and choosing a different one installs a "
					+ "carrier for one Minecraft under a profile claiming another. The rest of the file still applies.",
					file == null ? "the declaration" : file.getFileName(), String.join(", ", reserved));
		}
		ForbricConfig config = new ForbricConfig(parsed, file);
		config.warnUnknown();
		return config;
	}

	/** Every reserved key the file set, lowercased and in the order found. */
	private static List<String> rejectReserved(UnmodifiableConfig root) {
		List<String> found = new ArrayList<>();
		collectReserved(root, found);
		return found;
	}

	private static void collectReserved(UnmodifiableConfig node, List<String> found) {
		for (String key : node.valueMap().keySet()) {
			if (RESERVED.contains(key.toLowerCase(java.util.Locale.ROOT))) {
				found.add(key);
				continue;
			}
			Object value = node.get(key);
			if (value instanceof UnmodifiableConfig) collectReserved((UnmodifiableConfig) value, found);
		}
	}

	/** A key the running Forbric does not know, which a newer declaration may legitimately carry. */
	private void warnUnknown() {
		if (root == null) return;
		List<String> unknown = new ArrayList<>();
		collectUnknown(root, "", KNOWN_KEYS, unknown);
		if (unknown.isEmpty()) return;
		// source is null for an inline declaration, so the name is derived rather than taken. An NPE here would be
		// caught by the caller's handler and turned into "the file did not parse" — which is how a merely-unknown
		// key would silently discard every setting beside it.
		String where = source == null ? "the inline declaration" : source.getFileName().toString();
		ForbricLog.warn("[Forbric/Config] %s carries %d setting(s) this Forbric does not know: %s — ignored. An "
				+ "unknown key is not a reason to refuse the whole file.", where, unknown.size(),
				String.join(", ", unknown));
	}

	private void collectUnknown(UnmodifiableConfig node, String prefix, Set<String> known, List<String> out) {
		for (String key : node.valueMap().keySet()) {
			String path = prefix.isEmpty() ? key : prefix + "." + key;
			if (!known.contains(path)) out.add(path);
			Object value = node.get(key);
			if (value instanceof UnmodifiableConfig) collectUnknown((UnmodifiableConfig) value, path, known, out);
		}
	}

	/** Whether the file was found and parsed. False means every accessor below answers from the default. */
	public boolean present() {
		return root != null;
	}

	/** Where it was read from, or null when there is no file. */
	public Path source() {
		return source;
	}

	/**
	 * A boolean switch, as {@code on} / {@code off} — the spelling every existing {@code -Dforbric.*} switch uses.
	 *
	 * <p>Only a system property counts as an override here. A file that said {@code unifiedEvents = "on"} while
	 * {@code -Dforbric.unifiedEvents=off} is on the command line must not win: the property is the more immediate,
	 * more deliberate statement, and the file is what someone wrote some time ago.
	 */
	public boolean flag(String property, String key, boolean fallback) {
		String fromProperty = System.getProperty(property);
		if (fromProperty != null) return !fromProperty.equalsIgnoreCase("off");
		String value = value(property, key);
		if (value == null) return fallback;
		if (value.equalsIgnoreCase("off")) return false;
		if (value.equalsIgnoreCase("on")) return true;
		// A value that is neither is not silently coerced: the typo is the finding.
		ForbricLog.warn("[Forbric/Config] [%s] is \"%s\", which is neither \"on\" nor \"off\"; using the default (%s)",
				key, value, fallback ? "on" : "off");
		return fallback;
	}

	/**
	 * A raw string, or null when absent.
	 *
	 * <p>No {@code -D} consultation here on purpose: a config key like {@code mixin.suppress} is not a system
	 * property name, and prefixing it would invent a property nobody sets. A switch that HAS a property uses
	 * {@link #csv} / {@link #value}, which are told the property name explicitly.
	 *
	 * <p>A reserved key reads as absent even when the file set it, so no future accessor can pick up a measured value
	 * by asking for it by name.
	 */
	public String string(String key) {
		if (root == null || isReserved(key)) return null;
		Object value = lookup(key);
		return value == null ? null : String.valueOf(value);
	}

	/**
	 * One switch's string, with its system property consulted first.
	 *
	 * <p>The two sources are named separately because they genuinely differ: the property is
	 * {@code forbric.keepMixins}, flat and lower-camel, while the file key is {@code mixin.keep}. Deriving one from
	 * the other is how a file key ends up wired to a property nobody sets — and the switch then silently answers
	 * from the file alone, or from neither.
	 */
	public String value(String property, String key) {
		String override = System.getProperty(property);
		if (override != null) return override;
		return string(key);
	}

	/** Whether {@code key}, or its last segment, names a measured value this file may not carry. */
	private static boolean isReserved(String key) {
		if (RESERVED.contains(key.toLowerCase(java.util.Locale.ROOT))) return true;
		int dot = key.lastIndexOf('.');
		return dot >= 0 && RESERVED.contains(key.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
	}

	/** A raw boolean, for the switches that are {@code -Dfoo} rather than {@code on}/{@code off}. */
	public boolean bool(String property, String key, boolean fallback) {
		String fromProperty = System.getProperty(property);
		if (fromProperty != null) return Boolean.parseBoolean(fromProperty);
		String value = value(property, key);
		if (value == null) return fallback;
		if (value.equalsIgnoreCase("true")) return true;
		if (value.equalsIgnoreCase("false")) return false;
		ForbricLog.warn("[Forbric/Config] [%s] is \"%s\", which is neither \"true\" nor \"false\"; using the default (%s)",
				key, value, fallback);
		return fallback;
	}

	/** A comma-separated list switch, or an empty list. The property wins over the file, as everywhere else. */
	public List<String> csv(String property, String key) {
		String raw = value(property, key);
		if (raw == null || raw.isBlank()) return List.of();
		List<String> out = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (!part.isBlank()) out.add(part.trim());
		}
		return out;
	}

	/** As {@link #csv} for a switch that has no system property, only a file key. */
	public List<String> csv(String key) {
		return csv(null, key);
	}

	/** The one key path, or null. An intermediate section that is not a table reads as absent. */
	private Object lookup(String key) {
		if (root == null) return null;
		String[] parts = key.split("\\.");
		UnmodifiableConfig node = root;
		for (int i = 0; i < parts.length - 1; i++) {
			Object next = node.get(parts[i]);
			if (!(next instanceof UnmodifiableConfig)) return null;
			node = (UnmodifiableConfig) next;
		}
		return node.get(parts[parts.length - 1]);
	}

	/** The keys this instance actually set, dotted. For a report that says what the operator chose. */
	public Set<String> declared() {
		if (root == null) return Set.of();
		Set<String> out = new LinkedHashSet<>();
		declared(root, "", out);
		return out;
	}

	private void declared(UnmodifiableConfig node, String prefix, Set<String> out) {
		for (String key : node.valueMap().keySet()) {
			String path = prefix.isEmpty() ? key : prefix + "." + key;
			out.add(path);
			Object value = node.get(key);
			if (value instanceof UnmodifiableConfig) declared((UnmodifiableConfig) value, path, out);
		}
	}
}
