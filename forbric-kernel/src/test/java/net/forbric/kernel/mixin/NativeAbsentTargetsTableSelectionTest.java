/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;

/**
 * The table is one generation's measurement, so it is picked by the running Minecraft version.
 *
 * <p>Why it matters: the rows name Mojmap signatures read out of a specific carrier, and the trailing
 * {@code platform … minecraft=<v>} lines say which. Handing a 1.21.x run the 26.2 rows does not merely answer the
 * wrong question — {@link NativeAbsentTargets#unmetRequirement} compares a mod's own declared {@code minecraft}
 * range against the table's recorded version, so a mod that declares {@code 1.21.8} exactly is judged "not
 * satisfied" by a table that says {@code 26.2}, and its injector targets silently stop counting as native.
 */
class NativeAbsentTargetsTableSelectionTest {

	@AfterEach
	void clearProperties() {
		System.clearProperty(NativeAbsentTargets.TABLE_PROPERTY);
		System.clearProperty("forbric.mcVersion");
	}

	@Test
	void anUnsetVersionMeansTheDefaultGeneration() {
		assertEquals(NativeAbsentTargets.DEFAULT_TABLE_GENERATION, NativeAbsentTargets.runningGeneration());
		assertEquals(NativeAbsentTargets.TABLE, NativeAbsentTargets.tableResource());
	}

	@Test
	void theDefaultGenerationReadsTheCommittedTable() {
		System.setProperty("forbric.mcVersion", NativeAbsentTargets.DEFAULT_TABLE_GENERATION);
		assertEquals(NativeAbsentTargets.TABLE, NativeAbsentTargets.tableResource());
	}

	@Test
	void anotherGenerationAsksForItsOwnTable() {
		for (String mc : new String[] { "1.21.8", "1.21.1" }) {
			System.setProperty("forbric.mcVersion", mc);
			assertEquals("/net/forbric/kernel/mixin/native-only-methods-" + mc + ".txt",
					NativeAbsentTargets.tableResource(),
					mc + " must not be judged against another generation's rows");
		}
	}

	@Test
	void theOverridePropertyWinsOverThePerGenerationPick() {
		System.setProperty("forbric.mcVersion", "1.21.8");
		System.setProperty(NativeAbsentTargets.TABLE_PROPERTY, "/somewhere/else.txt");
		assertEquals("/somewhere/else.txt", NativeAbsentTargets.tableResource());
	}

	@Test
	void aBlankPropertyIsNoProperty() {
		System.setProperty("forbric.mcVersion", "   ");
		System.setProperty(NativeAbsentTargets.TABLE_PROPERTY, "");
		assertEquals(NativeAbsentTargets.DEFAULT_TABLE_GENERATION, NativeAbsentTargets.runningGeneration());
		assertEquals(NativeAbsentTargets.TABLE, NativeAbsentTargets.tableResource());
	}

	@Test
	void aTableOnDiskIsReadSoOneBeingMeasuredNeedsNoRebuild() throws Exception {
		Path table = Files.createTempFile("native-absent", ".txt");
		try {
			Files.writeString(table, String.join("\n",
					"platform fabric minecraft=1.21.8",
					"fabric net/minecraft/Foo#bar()V") + "\n", StandardCharsets.UTF_8);
			System.setProperty(NativeAbsentTargets.TABLE_PROPERTY, table.toString());

			NativeAbsentTargets.Table parsed = NativeAbsentTargets.Table.parse(
					Files.readAllLines(table, StandardCharsets.UTF_8));
			var rows = parsed.of(Ecosystem.FABRIC);
			assertEquals("1.21.8", rows.versions().get(NativeAbsentTargets.MINECRAFT),
					"the table's own generation is what unmetRequirement compares against");
		} finally {
			Files.deleteIfExists(table);
		}
	}

	@Test
	void aModDeclaringThatGenerationIsSatisfiedByItsOwnTable() {
		// The whole point: with the matching table, an exactly-declared 1.21.8 mod is NOT "unmet".
		var parsed = NativeAbsentTargets.Table.parse(List.of("platform fabric minecraft=1.21.8"));
		var rows = parsed.of(Ecosystem.FABRIC);
		assertEquals("1.21.8", rows.versions().get(NativeAbsentTargets.MINECRAFT));
		// And against the 26.2 table the same declaration reads as unmet — the bug this selection avoids.
		var other = NativeAbsentTargets.Table.parse(List.of("platform fabric minecraft=26.2"));
		assertNotEquals(rows.versions().get(NativeAbsentTargets.MINECRAFT),
				other.of(Ecosystem.FABRIC).versions().get(NativeAbsentTargets.MINECRAFT));
	}

	@Test
	void theCommittedTableNamesTheDefaultGeneration() throws Exception {
		// Guards the fallback path: the default table must actually claim 26.2, or the warning above lies.
		java.io.InputStream in = NativeAbsentTargets.class
				.getResourceAsStream(NativeAbsentTargets.TABLE);
		assertTrue(in != null, NativeAbsentTargets.TABLE + " is missing from the jar");
		String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(text.contains("platform fabric minecraft=" + NativeAbsentTargets.DEFAULT_TABLE_GENERATION),
				"the committed table does not declare " + NativeAbsentTargets.DEFAULT_TABLE_GENERATION);
	}
}
