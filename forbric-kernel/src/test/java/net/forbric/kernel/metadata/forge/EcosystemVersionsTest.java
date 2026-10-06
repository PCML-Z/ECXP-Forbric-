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

package net.forbric.kernel.metadata.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EcosystemVersionsTest {
	@TempDir
	Path dir;

	@BeforeEach
	@AfterEach
	void forget() {
		EcosystemVersions.reset();
	}

	@Test
	void aCarriersOwnManifestIsWhereTheVersionComesFrom() throws Exception {
		EcosystemVersions.record(List.of(carrier("neoforge-runtime.jar", "META-INF/neoforge.mods.toml",
				"neoforge", "26.2.0.7-beta")));
		assertEquals("26.2.0.7-beta", EcosystemVersions.provided("neoforge"));
		assertNull(EcosystemVersions.provided("forge"));
	}

	/**
	 * The MinecraftForge carrier ships {@code version="${global.forgeVersion}"} — a placeholder its build never
	 * substituted. Recording it made the audit accuse an honest mod of requiring a newer Forge than we provide.
	 * An unresolved placeholder is an ABSENT version, not a low one.
	 *
	 * <p>With no other source in the jar the carrier still contributes nothing — declining to judge beats judging
	 * confidently on a placeholder. {@link #aPlaceholderTomlFallsBackToTheJarsOwnManifest} covers the real jar,
	 * which does have another source.
	 */
	@Test
	void anUnresolvedPlaceholderIsNotAVersion() throws Exception {
		EcosystemVersions.record(List.of(carrier("forge-runtime.jar", "META-INF/mods.toml",
				"forge", "${global.forgeVersion}")));
		assertNull(EcosystemVersions.provided("forge"),
				"declining to judge beats judging confidently on a placeholder");
	}

	/**
	 * The MinecraftForge carrier's toml says {@code version="${global.forgeVersion}"}, so declining the placeholder
	 * left Forge with NO recorded version and the audit silent for every Forge mod — silence that read as health
	 * while an under-provisioned Forge mod loaded and failed later. The version is not missing, only the toml's
	 * copy of it: the same jar's manifest says {@code Implementation-Title: MinecraftForge} /
	 * {@code Implementation-Version: 65.0.1}. That is what a genuine MinecraftForge reports as {@code forge}'s
	 * version, so reading it reports the jar's own identity rather than inventing one.
	 */
	@Test
	void aPlaceholderTomlFallsBackToTheJarsOwnManifest() throws Exception {
		EcosystemVersions.record(List.of(carrier("forge-runtime.jar", "META-INF/mods.toml",
				"forge", "${global.forgeVersion}",
				"Manifest-Version: 1.0",
				"Implementation-Title: MinecraftForge",
				"Implementation-Version: 65.0.1")));
		assertEquals("65.0.1", EcosystemVersions.provided("forge"),
				"a carrier that cannot state its version in the toml still states it in the manifest");
	}

	/** A version with no title beside it cannot be attributed, and an unattributable version is declined. */
	@Test
	void aManifestWithNoTitleIsNotAttributable() throws Exception {
		EcosystemVersions.record(List.of(carrier("forge-runtime.jar", "META-INF/mods.toml",
				"forge", "${global.forgeVersion}",
				"Manifest-Version: 1.0",
				"Implementation-Version: 65.0.1")));
		assertNull(EcosystemVersions.provided("forge"));
	}

	/**
	 * Only the MAIN section describes this jar. A carrier bundles hundreds of libraries, each with its own
	 * {@code Name:} section carrying its own {@code Implementation-Version}; reading one of those would report a
	 * bundled library's version as the ecosystem's.
	 */
	@Test
	void aBundledLibrarysSectionIsNotTheCarriersVersion() throws Exception {
		EcosystemVersions.record(List.of(carrier("forge-runtime.jar", "META-INF/mods.toml",
				"forge", "${global.forgeVersion}",
				"Manifest-Version: 1.0",
				"Implementation-Title: MinecraftForge",
				"",
				"Name: org/some/bundled/library/",
				"Implementation-Version: 0.0.1")));
		assertNull(EcosystemVersions.provided("forge"),
				"the main section has no version; the bundled one belongs to a different jar");
	}

	/**
	 * Why the fallback exists: Classic Pipes declares {@code forge [65.0.9,)} and this instance provides 65.0.1.
	 * Before the fallback Forge had no version at all, so this was never evaluated — the mod loaded as though the
	 * range were satisfied. It is now named, and the loader says so instead of swallowing it.
	 */
	@Test
	void anUnderProvisionedForgeRangeIsNowCaught() throws Exception {
		EcosystemVersions.record(List.of(carrier("forge-runtime.jar", "META-INF/mods.toml",
				"forge", "${global.forgeVersion}",
				"Manifest-Version: 1.0",
				"Implementation-Title: MinecraftForge",
				"Implementation-Version: 65.0.1")));

		assertFalse(EcosystemVersions.accepts("forge", "[65.0.9,)"),
				"65.0.1 is below the declared floor; a genuine MinecraftForge would refuse this mod");
		assertTrue(EcosystemVersions.accepts("forge", "[65.0.0,)"),
				"a range we do satisfy must not be reported as a problem");

		ForgeModsToml classicPipes = ModsTomlParser.parse(("""
				modLoader="javafml"
				loaderVersion="[3,]"

				[[mods]]
				    modId="classicpipes"
				    version="1.0.0"
				    [[dependencies.classicpipes]]
				        modId="forge"
				        mandatory=true
				        versionRange="[65.0.9,)"
				        ordering="NONE"
				        side="BOTH"
				"""));
		String logged = capture(() -> EcosystemVersions.audit(classicPipes, "classicpipes.jar"));
		assertTrue(logged.contains("classicpipes"), "the warning names the mod: " + logged);
		assertTrue(logged.contains("[65.0.9,)"), "the warning names the requirement: " + logged);
		assertTrue(logged.contains("65.0.1"), "the warning names what this instance actually provides: " + logged);
	}

	@Test
	void aBlankVersionIsAlsoDeclined() throws Exception {
		EcosystemVersions.record(List.of(carrier("neoforge-runtime.jar", "META-INF/neoforge.mods.toml",
				"neoforge", "   ")));
		assertNull(EcosystemVersions.provided("neoforge"));
	}

	@Test
	void onlyEcosystemIdsAreRecorded() throws Exception {
		EcosystemVersions.record(List.of(carrier("somemod.jar", "META-INF/neoforge.mods.toml",
				"justamod", "1.2.3")));
		assertNull(EcosystemVersions.provided("justamod"));
	}

	@Test
	void nothingToReadIsNotAnError() {
		EcosystemVersions.record(null);
		EcosystemVersions.record(List.of());
		EcosystemVersions.record(List.of(dir.resolve("absent.jar")));
		assertNull(EcosystemVersions.provided("neoforge"));
		// audit must be equally unbothered
		EcosystemVersions.audit(null, "nowhere");
	}

	/** The first carrier to claim an ecosystem owns it; a second must not silently redefine what we provide. */
	@Test
	void theFirstCarrierToClaimAnEcosystemWins() throws Exception {
		EcosystemVersions.record(List.of(
				carrier("a.jar", "META-INF/neoforge.mods.toml", "neoforge", "26.2.0.7-beta"),
				carrier("b.jar", "META-INF/neoforge.mods.toml", "neoforge", "1.0.0")));
		assertEquals("26.2.0.7-beta", EcosystemVersions.provided("neoforge"));
	}

	private Path carrier(String name, String manifestPath, String modId, String version) throws Exception {
		return carrier(name, manifestPath, modId, version, (String[]) null);
	}

	/**
	 * A carrier jar holding a {@code mods.toml} and, when {@code manifestLines} is given, a {@code MANIFEST.MF}
	 * whose lines are joined with CRLF — the only line ending {@link java.util.jar.Manifest} accepts.
	 */
	private Path carrier(String name, String manifestPath, String modId, String version,
			String... manifestLines) throws Exception {
		Path jar = dir.resolve(name);
		String toml = """
				modLoader="javafml"
				loaderVersion="[3,]"
				license="LGPL v2.1"

				[[mods]]
				    modId="%s"
				    version="%s"
				""".formatted(modId, version);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry(manifestPath));
			OutputStream out = zip;
			out.write(toml.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();

			if (manifestLines != null) {
				zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
				out.write(String.join("\r\n", manifestLines).getBytes(StandardCharsets.UTF_8));
				out.write("\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return jar;
	}

	/** Everything written to {@code System.out} / {@code System.err} while {@code body} runs. */
	private static String capture(Runnable body) {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream sink = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		System.setOut(sink);
		System.setErr(sink);
		try {
			body.run();
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
		return buffer.toString(StandardCharsets.UTF_8);
	}
}
