/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import net.forbric.api.CompatibilityFinding;
import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.fabric.KernelModContainer;
import net.forbric.kernel.ui.CompatibilityDecision;

@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
@ResourceLock("KernelFabricEcosystem")
public class InitializationFailureProjectionTest {
	@TempDir Path directory;
	private static int constructorAttempts, entrypointAttempts, healthyRuns;
	@BeforeEach @AfterEach void reset() {
		ModCatalog.publish(List.of()); CompatibilityFindings.reset(); CompatibilityDecision.reset(); KernelLoadReport.reset();
		System.clearProperty(CompatibilityDecision.PROPERTY);
		constructorAttempts = entrypointAttempts = healthyRuns = 0;
	}

	@Test void actualEntrypointConstructionAndInvocationFailuresBecomeRequiredAtTheReportingBoundary() throws Exception {
		var constructor = KernelFabricLoader.class.getDeclaredConstructor(EnvType.class, Path.class, Path.class, String[].class, String.class);
		constructor.setAccessible(true);
		KernelFabricLoader loader = constructor.newInstance(EnvType.SERVER, directory, directory.resolve("config"), new String[0], "26.2");
		Field gameLoader = KernelFabricLoader.class.getDeclaredField("gameLoader"); gameLoader.setAccessible(true); gameLoader.set(loader, getClass().getClassLoader());
		register(loader, "badctor", ThrowsDuringConstruction.class);
		register(loader, "badentry", ThrowsDuringInitialization.class);
		register(loader, "healthy", Healthy.class); loader.freeze();
		ModCatalog.publish(List.of(entry("badctor", Ecosystem.FABRIC), entry("badentry", Ecosystem.FABRIC), entry("healthy", Ecosystem.FABRIC)));
		Field active = KernelFabricEcosystem.class.getDeclaredField("loader"); active.setAccessible(true); Object previous = active.get(null);
		String shim = System.getProperty(KernelForeignShimContext.SWITCH);
		try {
			active.set(null, loader); System.setProperty(KernelForeignShimContext.SWITCH, "off");
			var invoke = KernelFabricEcosystem.class.getDeclaredMethod("invoke", String.class, Class.class, Consumer.class); invoke.setAccessible(true);
			assertEquals(1, invoke.invoke(null, "main", ModInitializer.class, (Consumer<ModInitializer>) ModInitializer::onInitialize));
			assertEquals(1, constructorAttempts); assertEquals(1, entrypointAttempts); assertEquals(1, healthyRuns);
			assertTrue(CompatibilityFindings.all().isEmpty(), "the per-mod catches only record raw status, without prompting or callbacks");
			assertEquals(2, ModCatalog.failures().size());
			Path report = directory.resolve("load-report.txt"); KernelLoadReport.writeTo(report);
			assertEquals(List.of("badctor", "badentry"), CompatibilityFindings.confirmedRequired().stream().map(f -> f.modId()).toList());
			assertTrue(CompatibilityFindings.confirmedRequired().stream().allMatch(f -> f.id().equals("initialization:entrypoint:main")));
			String machine = Files.readString(directory.resolve("compatibility-report.json"));
			assertTrue(machine.contains("\"confirmedRequired\":2")); assertTrue(machine.contains("\"catalogFailures\":[]"));
			assertEquals(2, CompatibilityDecision.drain().size(), "the report queues necessary findings without displaying UI");
			System.setProperty(CompatibilityDecision.PROPERTY, "strict");
			assertThrows(CompatibilityDecision.LaunchStopped.class, () -> CompatibilityDecision.requireContinuation(false));
		} finally {
			active.set(null, previous);
			if (shim == null) System.clearProperty(KernelForeignShimContext.SWITCH); else System.setProperty(KernelForeignShimContext.SWITCH, shim);
		}
	}

	@Test void aThrowingConstructorAndADegradedRowBothRequireADecision() throws Exception {
		// DEGRADED used to be a NOTE: it reached load-report.txt as an UNCLASSIFIED line and the launch continued.
		// It now requires a decision like FAILED does, because a mod that is installed, loaded and reported as fine
		// while quietly not working is the failure this project keeps paying for. The writer is still a safe report
		// primitive — it never throws — so the escalation happens at the decision, not here.
		ModCatalog.publish(List.of(entry("badctor", Ecosystem.NEOFORGE), entry("optional", Ecosystem.FABRIC)));
		var thrown = assertThrows(InvocationTargetException.class, () -> ThrowsDuringConstruction.class.getConstructor().newInstance());
		assertInstanceOf(IllegalStateException.class, thrown.getCause());
		List<String> withdrawn = new ArrayList<>();
		assertTrue(KernelModLoader.keepConstructed(Map.of("badctor", new Object()), Set.of(), withdrawn).isEmpty());
		KernelModLoader.markWithdrawn(withdrawn, "its @Mod constructor threw");
		ModCatalog.mark("optional", ModCatalog.Status.DEGRADED, "optional setup feature failed");
		System.setProperty(CompatibilityDecision.PROPERTY, "strict");
		assertDoesNotThrow(() -> KernelLoadReport.writeTo(directory.resolve("load-report.txt")), "the writer remains a safe report primitive");

		var required = CompatibilityFindings.confirmedRequired();
		assertEquals(2, required.size(), "the constructor failure AND the degraded row both require a decision now");
		CompatibilityFinding constructor = required.stream()
				.filter(f -> f.modId().equals("badctor")).findFirst().orElseThrow();
		assertEquals("initialization:constructor", constructor.id());
		// The DEGRADED row is keyed by its reason text, because a dozen producers each describe themselves in their
		// own words and there is no vocabulary to enumerate them by.
		CompatibilityFinding degraded = required.stream()
				.filter(f -> f.modId().equals("optional")).findFirst().orElseThrow();
		assertEquals("initialization:degraded:optional setup feature failed", degraded.id());
		assertTrue(degraded.required(), "a DEGRADED row is required in full; the escape hatch is the policy, not this flag");
		assertTrue(degraded.evidence().contains("ModCatalog.Status.DEGRADED"),
				"the report has to say which status produced it, or an operator cannot tell a stop caused by a partial loss");

		String json = Files.readString(directory.resolve("compatibility-report.json"));
		assertTrue(json.contains("\"classification\":\"UNCLASSIFIED\"")); assertTrue(json.contains("optional setup feature failed"));
	}

	private static void register(KernelFabricLoader loader, String id, Class<?> entry) {
		String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"entrypoints\":{\"main\":[\"" + entry.getName() + "\"]}}";
		loader.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null));
	}
	private static ModCatalog.Entry entry(String id, Ecosystem ecosystem) {
		return new ModCatalog.Entry(ecosystem, id, id, "1", "", List.of(), id + ".jar", "", "");
	}
	public static final class ThrowsDuringConstruction implements ModInitializer {
		public ThrowsDuringConstruction() { constructorAttempts++; throw new IllegalStateException("intentional constructor failure"); }
		@Override public void onInitialize() { fail("constructor failed"); }
	}
	public static final class ThrowsDuringInitialization implements ModInitializer {
		@Override public void onInitialize() { entrypointAttempts++; throw new IllegalStateException("intentional entrypoint failure"); }
	}
	public static final class Healthy implements ModInitializer {
		@Override public void onInitialize() { healthyRuns++; }
	}
}
