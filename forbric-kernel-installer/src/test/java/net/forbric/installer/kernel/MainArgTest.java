package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A value-taking option with no value must not swallow the next option. `--dir --doctor` used to create a folder
 * literally named "--doctor" and install into it; `--release --offline` used to take "--offline" as a tag.
 * The parser calls System.exit, so drive it in a child JVM and judge the exit code and output.
 */
public final class MainArgTest {
	public static void main(String[] args) throws Exception {
		Path work = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
		int checks = 0;

		for (String[] argv : List.of(
				new String[] { "--dir", "--doctor" },
				new String[] { "--release", "--offline" },
				new String[] { "--mirror", "--remote" },
				new String[] { "--dir" })) {
			Result run = mainInChild(work, argv);
			require(run.code == 2, String.join(" ", argv) + " exited " + run.code + ": " + run.output);
			require(run.output.contains(argv[0] + " needs a value"),
					"the refusal must name the option: " + run.output);
			checks++;
		}

		// The swallowed flag used to be created as a directory in the current working directory.
		for (String flag : List.of("--doctor", "--offline", "--remote")) {
			require(!Files.exists(work.resolve(flag)), flag + " was created as a path");
		}

		System.out.println("PASS installer CLI argument values: " + checks + " checks");
	}

	private static Result mainInChild(Path work, String[] argv) throws IOException, InterruptedException {
		String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classpath = System.getProperty("java.class.path");
		List<String> command = new ArrayList<>();
		command.add(javaBin);
		command.add("-cp");
		command.add(classpath);
		command.add(Main.class.getName());
		command.addAll(List.of(argv));
		Process process = new ProcessBuilder(command).directory(work.toFile())
				.redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		return new Result(process.waitFor(), output);
	}

	private record Result(int code, String output) { }

	private static void require(boolean ok, String message) {
		if (!ok) throw new AssertionError(message);
	}
}
