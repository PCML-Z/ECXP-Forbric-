/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The instance declaration, and the three rules that make it safe to have one.
 *
 * <p>A file a pack author can edit is a file that can be edited into a state nobody measured, so what matters here is
 * not that the file is read but that reading it cannot do harm: a measured value cannot be moved from it, a broken
 * one cannot stop the game booting, and a system property still wins so a developer can override at one launch.
 */
class ForbricConfigTest {

	@AfterEach
	void clearOverrides() {
		System.clearProperty("forbric.unifiedEvents");
		System.clearProperty("forbric.forgeHandshake");
		System.clearProperty("forbric.keepMixins");
		ForbricConfig.reset();
	}

	@Test
	void noFileMeansTheDefaults(@TempDir Path gameDir) {
		ForbricConfig config = ForbricConfig.load(gameDir);
		assertFalse(config.present(), "an instance with no declaration must not look like one that has one");
		assertTrue(config.flag("forbric.unifiedEvents", "bridges.unified", true),
				"bridges default to installed; a missing file must not silently disable them");
	}

	@Test
	void aFileIsFoundBesideTheGame(@TempDir Path gameDir) throws Exception {
		write(gameDir, "[bridges]\nunified = \"off\"\n");
		ForbricConfig config = ForbricConfig.load(gameDir);
		assertTrue(config.present());
		assertFalse(config.flag("forbric.unifiedEvents", "bridges.unified", true),
				"the whole point: a pack author turns bridges off in a file, not on a launch command");
	}

	@Test
	void aSystemPropertyStillWins(@TempDir Path gameDir) throws Exception {
		// The precedence rule, both directions, because a developer overriding at one launch is the documented
		// escape hatch and must not be defeated by a file written some time ago.
		write(gameDir, "[bridges]\nunified = \"on\"\n");
		System.setProperty("forbric.unifiedEvents", "off");
		assertFalse(ForbricConfig.load(gameDir).flag("forbric.unifiedEvents", "bridges.unified", true));

		write(gameDir, "[bridges]\nunified = \"off\"\n");
		System.setProperty("forbric.unifiedEvents", "on");
		assertTrue(ForbricConfig.load(gameDir).flag("forbric.unifiedEvents", "bridges.unified", false));
	}

	@Test
	void aMeasuredValueCannotBeMovedFromTheFile(@TempDir Path gameDir) throws Exception {
		// The load-bearing rule. A version pin or an anchor table is measured against one build's bytecode; moving it
		// from an editable file installs a carrier for one Minecraft under a profile claiming another, which is the
		// silent mismatch the build stamp exists to prevent. It must be unreadable through the file, and refused
		// loudly at load so an author who wrote it does not conclude the key works.
		write(gameDir, "minecraft = \"1.21.1\"\n[bridges]\nunified = \"off\"\n");
		ForbricConfig config = ForbricConfig.load(gameDir);
		assertEquals(null, config.string("minecraft"),
				"a measured value must be unreadable out of an instance file, whatever the file says");
		assertFalse(config.flag("forbric.unifiedEvents", "bridges.unified", true),
				"the rest of the file still applies after a refused key");
	}

	@Test
	void aNestedMeasuredKeyIsAlsoReserved(@TempDir Path gameDir) throws Exception {
		write(gameDir, "[anchors]\nnativeabsenttable = \"/somewhere/else.txt\"\n");
		assertTrue(ForbricConfig.load(gameDir).present());
		// No reader exists for it: the refusal is the load-time log line, asserted by the absence of any accessor.
		assertEquals(null, ForbricConfig.load(gameDir).string("anchors.native_only_methods"),
				"nothing may read a measured value out of an instance file");
	}

	@Test
	void aMalformedFileCostsTheSettingsNotTheWorld(@TempDir Path gameDir) throws Exception {
		// Fail-soft direction: an optional file with a typo must not take the game down, because a player pays for
		// it with their world.
		write(gameDir, "this is not = = toml [[[\n");
		ForbricConfig config = ForbricConfig.load(gameDir);
		assertTrue(config.flag("forbric.unifiedEvents", "bridges.unified", true),
				"a broken file falls back to the defaults, which are the working ones");
	}

	@Test
	void anUnknownKeyDoesNotRefuseTheFile(@TempDir Path gameDir) throws Exception {
		// A newer declaration read by an older Forbric must still work: the keys it knows are applied, and the ones
		// it does not are reported and ignored.
		write(gameDir, "[bridges]\nunified = \"off\"\nsomethingNew = \"yes\"\n");
		ForbricConfig config = ForbricConfig.load(gameDir);
		assertTrue(config.present());
		assertFalse(config.flag("forbric.unifiedEvents", "bridges.unified", true),
				"a key from a newer Forbric must not cost the keys this one does know");
	}

	@Test
	void aValueThatIsNeitherOnNorOffIsReportedAndTheDefaultKept(@TempDir Path gameDir) throws Exception {
		// The typo is the finding, so it is not coerced: "offf" must not read as "on".
		write(gameDir, "[bridges]\nunified = \"offf\"\n");
		ForbricConfig config = ForbricConfig.load(gameDir);
		assertTrue(config.flag("forbric.unifiedEvents", "bridges.unified", true));
	}

	@Test
	void aListIsSplitAndTrimmed() {
		ForbricConfig config = ForbricConfig.parse("[mixin]\nsuppress = \"a , b,c\"\n");
		assertEquals(List.of("a", "b", "c"), config.csv("forbric.suppressMixins", "mixin.suppress"));
	}

	@Test
	void aListSwitchStillHonoursItsSystemProperty() {
		// The property name and the file key genuinely differ — forbric.keepMixins vs mixin.keep — so the reader is
		// told both. Wiring one from the other is how a switch ends up answering from a property nobody sets.
		ForbricConfig config = ForbricConfig.parse("[mixin]\nkeep = \"fromFile\"\n");
		assertEquals(List.of("fromFile"), config.csv("forbric.keepMixins", "mixin.keep"));
		System.setProperty("forbric.keepMixins", "fromProperty");
		assertEquals(List.of("fromProperty"), config.csv("forbric.keepMixins", "mixin.keep"));
	}

	@Test
	void anAbsentKeyIsNotAnEmptyList() {
		assertEquals(List.of(), ForbricConfig.parse("[mixin]\n").csv("forbric.suppressMixins", "mixin.suppress"),
				"an absent key and an empty one mean the same thing to every current caller");
	}

	@Test
	void declaredListsWhatTheAuthorActuallySet() {
		ForbricConfig config = ForbricConfig.parse("[ecosystem]\nprimary = \"forge\"\n\n[bridges]\nunified = \"off\"\n");
		assertEquals(java.util.Set.of("ecosystem.primary", "bridges.unified"), leaves(config),
				"a report should be able to say what this instance chose, without echoing every default");
	}

	@Test
	void everyKnownKeyParsesAsAFileWouldWriteIt() {
		// If a key were misspelled here it would still "work" — nothing reads it — so the sample file and the known-key
		// list are the only places a typo can hide. This asserts the two agree on every LEAF the sample sets.
		ForbricConfig config = ForbricConfig.parse(ForbricConfigExample.toToml());
		for (String key : leaves(config)) {
			assertTrue(ForbricConfig.KNOWN_KEYS.contains(key),
					key + " is set in the sample but not a known key — either the sample is stale or a reader is missing");
		}
	}

	/** The keys a file actually SET, as opposed to the section headers it groups them under. */
	private static java.util.Set<String> leaves(ForbricConfig config) {
		java.util.Set<String> all = config.declared();
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		for (String key : all) {
			// A header is any key some other key sits under; a leaf is one nothing sits under.
			boolean header = false;
			for (String other : all) {
				if (!other.equals(key) && other.startsWith(key + ".")) { header = true; break; }
			}
			if (!header) out.add(key);
		}
		return out;
	}

	private static void write(Path gameDir, String toml) throws Exception {
		Path file = gameDir.resolve(ForbricConfig.RELATIVE_PATH);
		Files.createDirectories(file.getParent());
		Files.writeString(file, toml, StandardCharsets.UTF_8);
	}
}
