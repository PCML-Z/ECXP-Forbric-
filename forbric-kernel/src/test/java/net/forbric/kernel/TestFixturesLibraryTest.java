/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The version JSON, not the file name that sorts last, chooses a library out of a multi-generation tree. */
class TestFixturesLibraryTest {
	@TempDir
	Path tmp;

	@Test
	void versionJsonBeatsTheNameThatSortsLast() throws Exception {
		Path libraries = tmp.resolve("libraries");
		write(libraries, "com/google/code/gson/gson/2.8.0/gson-2.8.0.jar");
		write(libraries, "com/google/code/gson/gson/2.14.0/gson-2.14.0.jar");
		write(libraries, "com/mojang/datafixerupper/6.0.8/datafixerupper-6.0.8.jar");
		write(libraries, "com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar");
		versionJson("""
				{"libraries":[
				  {"downloads":{"artifact":{"path":"com/google/code/gson/gson/2.8.0/gson-2.8.0-natives.jar"}}},
				  {"downloads":{"artifact":{"path":"com/google/code/gson/gson/2.14.0/gson-2.14.0.jar"}}},
				  {"downloads":{"artifact":{"path":"com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar"}}}
				]}
				""");

		assertEquals(libraries.resolve("com/google/code/gson/gson/2.14.0/gson-2.14.0.jar"),
				TestFixtures.minecraftLibrary(libraries, "com/google/code/gson/gson", "26.2"));
		assertEquals(libraries.resolve("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar"),
				TestFixtures.minecraftLibrary(libraries, "com/mojang/datafixerupper", "26.2"));
		assertEquals(List.of(
				libraries.resolve("com/google/code/gson/gson/2.14.0/gson-2.14.0.jar"),
				libraries.resolve("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar")),
				TestFixtures.minecraftLibraries(libraries, "26.2"));
	}

	@Test
	void withoutAVersionJsonTheLastNameRemains() throws Exception {
		Path libraries = tmp.resolve("libraries");
		write(libraries, "com/google/code/gson/gson/2.8.0/gson-2.8.0.jar");
		write(libraries, "com/google/code/gson/gson/2.14.0/gson-2.14.0.jar");

		assertEquals(libraries.resolve("com/google/code/gson/gson/2.8.0/gson-2.8.0.jar"),
				TestFixtures.minecraftLibrary(libraries, "com/google/code/gson", "26.2"));
	}

	@Test
	void aNamedJarThatIsMissingIsNotReplaced() throws Exception {
		Path libraries = tmp.resolve("libraries");
		write(libraries, "com/google/code/gson/gson/2.8.0/gson-2.8.0.jar");
		versionJson("""
				{"libraries":[{"downloads":{"artifact":{"path":"com/google/code/gson/gson/2.14.0/gson-2.14.0.jar"}}}]}
				""");

		assertNull(TestFixtures.minecraftLibrary(libraries, "com/google/code/gson/gson", "26.2"));
	}

	private void versionJson(String json) throws IOException {
		Path version = tmp.resolve("versions/26.2");
		Files.createDirectories(version);
		Files.writeString(version.resolve("26.2.json"), json);
	}

	private static void write(Path libraries, String relative) throws IOException {
		Path file = libraries.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "jar");
	}
}
