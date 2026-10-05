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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * The three heavy jars a Forbric instance runs on, when someone supplies them instead of letting the installer build
 * them: the byte-merged game base and one runtime per Forge family.
 *
 * <p>They are not in this installer and never will be. The merged base is Minecraft with two loaders' patches
 * applied and then merged, and the runtimes are assembled from MinecraftForge's and NeoForge's own distributions —
 * so all three embed code this project has no right to hand out. A player never needs this class: with no
 * directory given, {@link ArtifactBuilder} builds all three during the install. It serves developers who already
 * built them from source ({@code forbric-loader/run/build-merged-base.sh} and its two {@code assemble-*-runtime.sh}
 * siblings) and want to skip that.
 *
 * <p>A missing artifact is reported by name and expected path rather than guessed at, because every later step —
 * the profile's game arguments above all — is a lie without it. A FOUND artifact is then opened and checked for
 * what only the real one carries ({@link #verifyContents}), because a file name is not an identity.
 */
final class GameArtifacts {
	/** Coordinate → the file name the build scripts produce. */
	private static final Map<String, String> WANTED = new LinkedHashMap<>();

	static {
		WANTED.put(ArtifactBuilder.MERGED, "patched-mc-merged-%s.jar");
		// The INTEROP jar under the forge-runtime coordinate, as ArtifactBuilder stages it and as
		// build-merged-base.sh and gate-m0 link-check it. The raw forge-runtime.jar lacks the bridge methods the
		// merge makes necessary (an AbstractMethodError in game), and a link check cannot see a missing
		// implementation -- so it is never picked up here, whichever directory holds it.
		WANTED.put(ArtifactBuilder.FORGE_RUNTIME, "forge-runtime-interop.jar");
		WANTED.put(ArtifactBuilder.NEOFORGE_RUNTIME, "neoforge-runtime.jar");
	}

	/**
	 * What to do about any Built-artifacts error, said the same way everywhere. For everyone who is not developing
	 * Forbric the answer is to supply nothing: the installer then builds all three itself.
	 */
	static final String LEAVE_EMPTY = "Leave \"Built artifacts\" empty (on the command line: leave out --artifacts) "
			+ "and the installer downloads and builds these files itself.";

	// What only the real artifacts carry; see problem() for how these were chosen.
	private static final String MINECRAFT_CLIENT = "net/minecraft/client/Minecraft.class";
	private static final String FORGE_CORE = "net/minecraftforge/common/MinecraftForge.class";
	private static final String NEO_CORE = "net/neoforged/neoforge/common/NeoForge.class";
	private static final List<String> FORGE_LOADER = List.of(
			"net/minecraftforge/fml/loading/FMLLoader.class",
			"net/minecraftforge/forgespi/language/IModInfo.class");
	private static final List<String> NEO_LOADER = List.of(
			"net/neoforged/fml/loading/FMLLoader.class",
			"net/neoforged/neoforgespi/language/IModInfo.class");
	private static final byte[] FORGE_REFERENCE = "net/minecraftforge/".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] NEO_REFERENCE = "net/neoforged/".getBytes(StandardCharsets.US_ASCII);

	/**
	 * The one method {@code RuntimeInteropPatcher} adds to MinecraftForge's runtime, and so the one thing that
	 * tells {@code forge-runtime-interop.jar} from the {@code forge-runtime.jar} it is made from: same entries,
	 * same manifest, and in the raw jar this class has no {@code contents()} at all. The {@code .pins} stamp is no
	 * marker — only the installer's own build directory writes one; the development scripts' {@code run/} and an
	 * installed {@code libraries/} tree have none.
	 */
	private static final String INTEROP_CLASS = "net/minecraftforge/registries/NamespacedWrapper$3.class";
	private static final String INTEROP_METHOD = "contents";
	private static final String INTEROP_DESCRIPTOR = "()Ljava/util/Map;";

	private final Path dir;
	private final Map<String, Path> found = new LinkedHashMap<>();
	private final List<String> missing = new ArrayList<>();

	private GameArtifacts(Path dir) {
		this.dir = dir;
	}

	/**
	 * Locates all three for {@code mcVersion} in {@code dir}, or in the subdirectories the build scripts write into
	 * ({@code merged-base/}, {@code forge-runtime/}, {@code neoforge-runtime/}) — so the scripts' own
	 * {@code forbric-loader/run} can be named as it is.
	 *
	 * <p>Only the named directory. This used to fall back to the {@code run/} directory of whatever checkout the
	 * installer jar sat in, from when that fallback was how the installer found anything at all. Since the
	 * installer builds the artifacts itself when no directory is named, the fallback only ever completed a
	 * directory someone DID name with files from somewhere else: a set mixed from two builds, reported under a
	 * directory that did not hold it, and tests whose "this file is missing" case quietly passed or failed
	 * depending on whether the checkout running them had built its own artifacts.
	 */
	static GameArtifacts locate(String mcVersion, Path dir) throws IOException {
		GameArtifacts artifacts = find(mcVersion, dir);
		if (!artifacts.missing.isEmpty()) throw artifacts.refusal(Map.of());
		return artifacts;
	}

	/**
	 * What {@link #locate} looks for, file by file, without refusing an incomplete set: {@link #all} holds what is
	 * there and {@link #missing} names what is not. {@code --doctor} reports a half-filled directory this way; it
	 * used to call every file in one missing, the ones that were there included, because locate refuses the set
	 * as a whole.
	 */
	static GameArtifacts find(String mcVersion, Path dir) {
		GameArtifacts artifacts = new GameArtifacts(dir);
		for (Map.Entry<String, String> wanted : WANTED.entrySet()) {
			String fileName = String.format(wanted.getValue(), mcVersion);
			Path hit = null;
			for (Path candidate : new Path[] {
					dir.resolve(fileName),
					dir.resolve("merged-base").resolve(fileName),
					dir.resolve("forge-runtime").resolve(fileName),
					dir.resolve("neoforge-runtime").resolve(fileName)}) {
				if (Files.isRegularFile(candidate)) {
					hit = candidate;
					break;
				}
			}
			if (hit == null) artifacts.missing.add(fileName);
			else artifacts.found.put(wanted.getKey(), hit);
		}
		return artifacts;
	}

	/** The file names {@link #find} looked for and did not find. */
	List<String> missing() {
		return missing;
	}

	/**
	 * Opens every located artifact and refuses the set unless each one is what its name says it is — before
	 * anything is staged or a profile is written.
	 *
	 * <p>Issue #13: a player filled "Built artifacts" with three unrelated jars that happened to carry the right
	 * names. Nothing looked inside them. The link check passed, because a jar that refers to nothing outside
	 * itself leaves nothing dangling; the install reported success; and the game died at the first NeoForge class
	 * with five lines in latest.log and no crash report. Every bad file is listed at once, so fixing one does not
	 * just reveal the next on the following attempt.
	 */
	void verifyContents(String mcVersion) throws IOException {
		Map<String, String> problems = contentProblems(mcVersion);
		if (!problems.isEmpty()) throw refusal(problems);
	}

	/** coordinate → why its file is not what its name says; empty when every located file is. */
	Map<String, String> contentProblems(String mcVersion) {
		Map<String, String> problems = new LinkedHashMap<>();
		for (Map.Entry<String, Path> e : found.entrySet()) {
			String problem = problem(e.getKey(), e.getValue(), mcVersion);
			if (problem != null) problems.put(e.getKey(), problem);
		}
		return problems;
	}

	/**
	 * The refusal for this set: the files that are not there, then each file in {@code problems} by its full path
	 * and what is wrong with it, then the way out. An install never gets both halves at once — it does not open
	 * an incomplete set — but {@code --doctor} does, and says both.
	 */
	IOException refusal(Map<String, String> problems) {
		List<String> lines = new ArrayList<>();
		if (!missing.isEmpty()) {
			lines.add("Built artifacts: cannot find " + String.join(", ", missing) + " in " + dir + ".");
		}
		if (!problems.isEmpty()) {
			lines.add("Built artifacts: " + (problems.size() == 1 ? "this file is" : "these files are")
					+ " not the game files Forbric needs.");
			for (Map.Entry<String, String> e : problems.entrySet()) {
				lines.add("  " + found.get(e.getKey()) + "\n    " + e.getValue());
			}
		}
		lines.add(LEAVE_EMPTY);
		if (!missing.isEmpty()) {
			lines.add("Developers: the directory must hold what forbric-loader/run/build-merged-base.sh and the two "
					+ "assemble-*-runtime.sh scripts write (build-merged-base.sh also writes "
					+ "merged-base/forge-runtime-interop.jar), or be that run/ directory itself.");
		}
		return new IOException(String.join("\n", lines));
	}

	/**
	 * Why {@code jar} cannot stand for {@code coordinate}, in words a player can act on, or null when it can.
	 *
	 * <p>The markers were chosen against the real artifacts — the ones the development scripts stage and the ones
	 * {@link ArtifactBuilder} builds — and against what a player is likely to pick up instead: the MinecraftForge,
	 * NeoForge and Fabric installers, the vanilla client jar, each family's {@code -universal} jar, and each
	 * family's half-patched Minecraft from {@code .forbric-build/out}. Every real artifact has all of its markers;
	 * none of the others has all of them.
	 *
	 * <ul>
	 *   <li>The merged base is Minecraft {@code mcVersion} (the id in its {@code version.json}, and the client's
	 *       {@code Minecraft} class) whose own classes refer to BOTH {@code net/minecraftforge/} and
	 *       {@code net/neoforged/}: that is what carrying both families' patches looks like in bytecode. Vanilla
	 *       refers to neither, and each half-patched jar to only its own family. In the real merged base hundreds
	 *       of classes refer to each, so this does not hinge on any one class keeping one hook.</li>
	 *   <li>Each runtime holds its family's core class AND its family's mod loader. A {@code -universal} jar has
	 *       the first without the second; the kernel needs both, and the loader half is exactly what was absent
	 *       in #13 ({@code net/neoforged/neoforgespi/language/IModInfo}).</li>
	 *   <li>Each runtime was built for the version this installer pins: the {@code Implementation-Version} in its
	 *       manifest's main section, which both runtime builders (and both {@code assemble-*-runtime.sh}) write
	 *       from the pin — {@link Pins#NEOFORGE}, and {@link Pins#FORGE}'s FML half ({@code 65.0.1}). A runtime
	 *       from an older install passes every check above: 0.2.0's NeoForge runtime, {@code 26.2.0.38-beta},
	 *       installed without a word.</li>
	 *   <li>The MinecraftForge runtime is the interop-patched one ({@link #INTEROP_CLASS}), not
	 *       {@code forge-runtime.jar} renamed — which passes everything else and fails in game with an
	 *       {@code AbstractMethodError}.</li>
	 * </ul>
	 */
	static String problem(String coordinate, Path jar, String mcVersion) {
		ZipFile opened;
		try {
			opened = new ZipFile(jar.toFile());
		} catch (ZipException notAZip) {
			return "It is not a jar file: it cannot be opened as one.";
		} catch (IOException unreadable) {
			return "It cannot be read: " + unreadable.getMessage();
		}
		Pins.PinSet pins = Pins.forVersion(mcVersion);
		try (ZipFile zip = opened) {
			if (zip.size() == 0) return "It is an empty archive, with no files in it.";
			String installer = installerName(zip);
			if (installer != null) return "It is " + installer + ", not " + what(coordinate, mcVersion) + ".";
			return switch (coordinate) {
				case ArtifactBuilder.MERGED -> mergedBaseProblem(zip, mcVersion);
				case ArtifactBuilder.FORGE_RUNTIME -> {
					String problem = runtimeProblem(zip, "MinecraftForge", FORGE_CORE, FORGE_LOADER,
							new ForgeArtifacts(mcVersion, pins.forge()).fmlVersion);
					yield problem != null ? problem : interopProblem(zip);
				}
				case ArtifactBuilder.NEOFORGE_RUNTIME -> runtimeProblem(zip, "NeoForge", NEO_CORE, NEO_LOADER,
						pins.neoforge());
				default -> throw new IllegalArgumentException("not a game artifact: " + coordinate);
			};
		} catch (IOException damaged) {
			// It opened, so it is a jar; an entry inside it did not read back.
			return "It is damaged: part of it cannot be read (" + damaged.getMessage() + ").";
		}
	}

	private static String what(String coordinate, String mcVersion) {
		return switch (coordinate) {
			case ArtifactBuilder.MERGED -> "the merged game base (Minecraft " + mcVersion
					+ " with both MinecraftForge's and NeoForge's patches)";
			case ArtifactBuilder.FORGE_RUNTIME -> "the MinecraftForge runtime";
			case ArtifactBuilder.NEOFORGE_RUNTIME -> "the NeoForge runtime";
			default -> coordinate;
		};
	}

	private static String mergedBaseProblem(ZipFile zip, String mcVersion) throws IOException {
		String id = versionId(zip);
		if (id == null || zip.getEntry(MINECRAFT_CLIENT) == null) {
			return "It does not contain Minecraft" + looksLike(zip, null) + ".";
		}
		if (!id.equals(mcVersion)) {
			return "It is Minecraft " + id + ", but this install is for Minecraft " + mcVersion + ".";
		}
		boolean forge = false;
		boolean neo = false;
		for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();
			if (!name.startsWith("net/minecraft/") || !name.endsWith(".class")) continue;
			byte[] bytes;
			try (InputStream in = zip.getInputStream(entry)) {
				bytes = in.readAllBytes();
			}
			forge |= contains(bytes, FORGE_REFERENCE);
			neo |= contains(bytes, NEO_REFERENCE);
			if (forge && neo) return null;
		}
		if (!forge && !neo) {
			return "It is plain Minecraft " + mcVersion + ", not the merged game base Forbric builds from it.";
		}
		return "It is Minecraft " + mcVersion + " patched by " + (forge ? "MinecraftForge" : "NeoForge")
				+ " only. The merged game base carries both MinecraftForge's and NeoForge's patches.";
	}

	private static String runtimeProblem(ZipFile zip, String family, String core, List<String> loader,
			String pinned) {
		if (zip.getEntry(core) == null) return "It does not contain " + family + looksLike(zip, family) + ".";
		for (String marker : loader) {
			if (zip.getEntry(marker) == null) {
				return "It contains " + family + " but not the mod loader that belongs with it, so it is not the "
						+ family + " runtime Forbric puts together (" + family + "'s -universal jar looks like this).";
			}
		}
		String built = implementationVersion(zip);
		if (built == null) {
			return "It does not say which " + family + " it was built for (its manifest has no "
					+ "Implementation-Version); this installer needs " + pinned + ".";
		}
		if (!built.equals(pinned)) {
			return "It was built for " + family + " " + built + "; this installer needs " + pinned + ".";
		}
		return null;
	}

	private static String interopProblem(ZipFile zip) throws IOException {
		ZipEntry entry = zip.getEntry(INTEROP_CLASS);
		byte[] bytes = null;
		if (entry != null) {
			try (InputStream in = zip.getInputStream(entry)) {
				bytes = in.readAllBytes();
			}
		}
		if (bytes != null && declaresMethod(bytes, INTEROP_METHOD, INTEROP_DESCRIPTOR)) return null;
		return "It is forge-runtime.jar, the MinecraftForge runtime before Forbric patches it to fit the merged "
				+ "game base; forge-runtime-interop.jar is the patched one.";
	}

	/**
	 * The {@code Implementation-Version} of the manifest's main section, or null. Only the main section: the
	 * MinecraftForge runtime's manifest also carries one per bundled library ({@code AccessTransformers 8.2.2},
	 * {@code Bootstrap 2.1.8}, ...), and none of those says what the runtime was built for.
	 */
	private static String implementationVersion(ZipFile zip) {
		ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
		if (entry == null) return null;
		try (InputStream in = zip.getInputStream(entry)) {
			String version = new Manifest(in).getMainAttributes().getValue("Implementation-Version");
			return version == null || version.isBlank() ? null : version.strip();
		} catch (IOException unreadable) {
			return null;
		}
	}

	/**
	 * Whether a class file declares the method {@code name}{@code descriptor}, read from its own method table; a
	 * file that does not parse as a class declares nothing. Searching the bytes for the name would also match a
	 * class that only CALLS such a method; this installer carries no ASM, and walking the constant pool to the
	 * method table is all that is needed.
	 */
	private static boolean declaresMethod(byte[] classFile, String name, String descriptor) {
		try {
			return methodTableHas(new DataInputStream(new ByteArrayInputStream(classFile)), name, descriptor);
		} catch (IOException | RuntimeException malformed) {
			return false;
		}
	}

	private static boolean methodTableHas(DataInputStream in, String name, String descriptor) throws IOException {
		if (in.readInt() != 0xCAFEBABE) return false;
		in.skipNBytes(4); // minor, major
		int count = in.readUnsignedShort();
		String[] utf8 = new String[count];
		for (int i = 1; i < count; i++) {
			int tag = in.readUnsignedByte();
			switch (tag) {
				case 1 -> utf8[i] = in.readUTF(); // the class file's modified UTF-8 is exactly readUTF's format
				case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
				case 15 -> in.skipNBytes(3);
				case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
				case 5, 6 -> { // a long or double takes two constant-pool slots
					in.skipNBytes(8);
					i++;
				}
				default -> throw new IOException("not a class file: constant-pool tag " + tag);
			}
		}
		in.skipNBytes(6); // access flags, this class, super class
		in.skipNBytes(2L * in.readUnsignedShort()); // interfaces
		skipMembers(in); // fields
		for (int methods = in.readUnsignedShort(); methods > 0; methods--) {
			in.skipNBytes(2); // access flags
			String methodName = utf8[in.readUnsignedShort()];
			String methodDescriptor = utf8[in.readUnsignedShort()];
			if (name.equals(methodName) && descriptor.equals(methodDescriptor)) return true;
			skipAttributes(in);
		}
		return false;
	}

	private static void skipMembers(DataInputStream in) throws IOException {
		for (int members = in.readUnsignedShort(); members > 0; members--) {
			in.skipNBytes(6); // access flags, name, descriptor
			skipAttributes(in);
		}
	}

	private static void skipAttributes(DataInputStream in) throws IOException {
		for (int attributes = in.readUnsignedShort(); attributes > 0; attributes--) {
			in.skipNBytes(2); // name
			in.skipNBytes(in.readInt() & 0xFFFFFFFFL);
		}
	}

	/** A hint at what a wrong file actually is, when that is recognisable — the usual mistake is a swap. */
	private static String looksLike(ZipFile zip, String notThis) {
		if (!"NeoForge".equals(notThis) && zip.getEntry(NEO_CORE) != null) return " (it looks like NeoForge instead)";
		if (!"MinecraftForge".equals(notThis) && zip.getEntry(FORGE_CORE) != null) {
			return " (it looks like MinecraftForge instead)";
		}
		if (zip.getEntry(MINECRAFT_CLIENT) != null) return " (it looks like Minecraft instead)";
		return "";
	}

	/**
	 * The installer a player most plausibly downloaded instead, or null. MinecraftForge's and NeoForge's installers
	 * share one layout (NeoForge's is a fork) and are told apart by the version id they would install.
	 */
	private static String installerName(ZipFile zip) {
		if (zip.getEntry("install_profile.json") != null) {
			String id = versionId(zip);
			String lower = id == null ? "" : id.toLowerCase(Locale.ROOT);
			if (lower.contains("neoforge")) return "the NeoForge installer";
			if (lower.contains("forge")) return "the MinecraftForge installer";
			return "a mod loader's installer";
		}
		if (zip.stream().anyMatch(e -> e.getName().startsWith("net/fabricmc/installer/"))) {
			return "the Fabric installer";
		}
		return null;
	}

	/** The {@code id} in a jar's {@code version.json}, or null when it has none that reads as one. */
	private static String versionId(ZipFile zip) {
		ZipEntry entry = zip.getEntry("version.json");
		if (entry == null) return null;
		try (InputStream in = zip.getInputStream(entry)) {
			Object parsed = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			return parsed instanceof Map<?, ?> map && map.get("id") instanceof String id ? id : null;
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	/** coordinate (without version) → located file. */
	Map<String, Path> all() {
		return found;
	}

	Path get(String coordinate) {
		return found.get(coordinate);
	}
}
