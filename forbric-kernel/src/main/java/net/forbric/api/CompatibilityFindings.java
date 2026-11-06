/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-launch evidence ledger shared by boot code, the game UI and release checks. */
public final class CompatibilityFindings {
	private static final Map<String, CompatibilityFinding> FINDINGS = new LinkedHashMap<>();
	private static volatile long revision;

	private CompatibilityFindings() { }

	/**
	 * Observe the RAW catalogue only at a loading/report/decision boundary. FAILED has two audited production
	 * sources: withdrawn @Mod construction, and Fabric main/client/server entrypoint failure. Partial setup and
	 * optional feature losses use DEGRADED and are deliberately not promoted here. This never calls mark(), reads
	 * the projected catalogue, or infers recovery from a later catalogue publication.
	 *
	 * <p><b>DEGRADED is promoted too, and that is a deliberate policy choice with a cost.</b> A DEGRADED row means
	 * part of a mod did not run: a setup phase threw, a mixin was suppressed, a field it reads drifted, a capability
	 * it declares nobody implements. The launch used to continue past all of that with a WARN and a line in
	 * {@code load-report.txt}, which is the failure this project keeps paying for — a mod that is installed,
	 * loaded, and reported as fine while quietly not working. A player cannot act on that; they can act on a launch
	 * that stops and names the mod.
	 *
	 * <p>What that costs is honest and worth stating plainly. DEGRADED is deliberately over-reporting by design
	 * ({@code FabricApiModuleLossAudit} chose the conservative direction for exactly this reason), and some of its
	 * producers are inferences rather than certainties: a mod's optional dependency may be absent on purpose, and a
	 * mixin the mod's own plugin declined is not a fault at all. So instances that used to start may now stop, and
	 * some of those stops will be false. The escape hatch is unchanged and still works — {@code
	 * forbric.compatibilityPolicy=continue} in {@code forbric/forbric.toml} — and it is the same lever that was
	 * always there for a required loss, so an operator who hits this has a documented way forward rather than a
	 * dead end.
	 */
	public static synchronized void observeInitializationFailures() {
		for (ModCatalog.Entry entry : ModCatalog.unclassifiedFailures()) {
			if (entry.status() != ModCatalog.Status.FAILED && entry.status() != ModCatalog.Status.DEGRADED) continue;
			// A jar whose metadata could not be read already carries its classified finding (metadata:<family>);
			// its row exists only so that finding has somewhere to show. A second, catalog-derived one would count
			// the same loss twice.
			if (FINDINGS.values().stream().anyMatch(f -> f.modId().equals(entry.modId())
					&& f.id().startsWith("metadata:") && f.confirmedRequired())) continue;
			for (CompatibilityFinding observed : initializationFindings(entry)) {
				CompatibilityFinding prior = FINDINGS.get(observed.key());
				if (prior != null && prior.confidence() == observed.confidence() && prior.required() == observed.required()
						&& prior.source().equals(observed.source()) && prior.detail().equals(observed.detail())
						&& prior.evidence().containsAll(observed.evidence())) continue;
				record(observed);
			}
		}
	}

	private static List<CompatibilityFinding> initializationFindings(ModCatalog.Entry entry) {
		List<CompatibilityFinding> result = new ArrayList<>();
		String detail = entry.statusDetail().isBlank() ? "The mod did not finish required initialization" : entry.statusDetail();
		boolean failed = entry.status() == ModCatalog.Status.FAILED;
		for (String reason : detail.split("; ")) {
			String phase;
			String source;
			// A withdrawn NeoForge mod's reason names the @Mod classes that threw after the plain one.
			String kind = reason.startsWith("its @Mod constructor threw (") ? "its @Mod constructor threw" : reason;
			switch (kind) {
				case "its @Mod constructor threw" -> { phase = "constructor"; source = "KernelModLoader @Mod construction"; }
				case "its preLaunch entrypoint threw" -> { phase = "entrypoint:preLaunch"; source = "KernelFabricEcosystem preLaunch entrypoint"; }
				case "its main entrypoint threw" -> { phase = "entrypoint:main"; source = "KernelFabricEcosystem main entrypoint"; }
				case "its client entrypoint threw" -> { phase = "entrypoint:client"; source = "KernelFabricEcosystem client entrypoint"; }
				case "its server entrypoint threw" -> { phase = "entrypoint:server"; source = "KernelFabricEcosystem server entrypoint"; }
				default -> {
					// A FAILED row may also retain earlier DEGRADED reasons. They are not new necessary
					// failures merely because a later client/main constructor failure raised the row's status.
					if (failed) continue;
					// A DEGRADED row, by contrast, is required in full. Its reasons come from a dozen producers
					// that each describe themselves in their own words — a suppressed mixin, a drifted field, a
					// capability nobody implements, a plugin that declined its own mixin — so there is no
					// vocabulary to enumerate them by, and the reason text is the only description any of them
					// carries. Keyed by the text so the same reason on two rows stays one finding.
					phase = "degraded:" + reason;
					source = "ModCatalog.Status.DEGRADED";
				}
			}
			result.add(new CompatibilityFinding("initialization:" + phase, entry.modId(), "Mod initialization", source,
					CompatibilityFinding.Confidence.CONFIRMED, true, reason,
					List.of("ModCatalog.Status." + entry.status(), "phase=" + phase, "ecosystem=" + entry.ecosystem(),
							"jar=" + entry.jar(), "version=" + entry.version(), reason)));
		}
		if (result.isEmpty()) {
			// Unknown producers still require a decision, but the aggregate state is the evidence:
			// do not infer that each prose fragment was a separate necessary initialization phase.
			String id = "initialization:catalog:" + java.util.UUID.nameUUIDFromBytes(detail.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			result.add(new CompatibilityFinding(id, entry.modId(), "Mod initialization",
					"ModCatalog.Status." + entry.status(),
					CompatibilityFinding.Confidence.CONFIRMED, true, detail,
					List.of("ModCatalog.Status." + entry.status(), detail)));
		}
		return result;
	}

	/**
	 * A later suspicion cannot erase a confirmed loss; repeated observations retain all evidence.
	 *
	 * <p>The revision moves only when the ledger does. A producer on a hot path -- a spawner call site the upgrade
	 * could not prove records its finding on every spawn -- repeats the same observation, and the readers of
	 * {@link #revision()} re-decide and rewrite the reports each time it moves.
	 */
	public static synchronized void record(CompatibilityFinding finding) {
		CompatibilityFinding previous = FINDINGS.get(finding.key());
		if (previous == null) {
			FINDINGS.put(finding.key(), finding);
			revision++;
			return;
		}
		CompatibilityFinding chosen = previous.confidence() != CompatibilityFinding.Confidence.SUSPECTED
				&& finding.confidence() == CompatibilityFinding.Confidence.SUSPECTED ? previous : finding;
		List<String> evidence = new ArrayList<>(previous.evidence());
		for (String item : finding.evidence()) if (!evidence.contains(item)) evidence.add(item);
		CompatibilityFinding merged = new CompatibilityFinding(chosen.id(), chosen.modId(), chosen.feature(),
				chosen.source(), chosen.confidence(), chosen.required(), chosen.detail(), evidence);
		if (merged.equals(previous)) return;
		FINDINGS.put(finding.key(), merged);
		revision++;
	}

	/** A repair or a plugin declining its own mixin can discharge a previously reported contract. */
	public static synchronized void resolve(String id, String modId, String proof) {
		CompatibilityFinding previous = FINDINGS.get(modId + ":" + id);
		if (previous == null) return;
		record(new CompatibilityFinding(previous.id(), previous.modId(), previous.feature(), previous.source(),
				CompatibilityFinding.Confidence.RESOLVED, previous.required(), proof, List.of(proof)));
	}

	public static synchronized List<CompatibilityFinding> all() {
		return FINDINGS.values().stream().sorted(java.util.Comparator.comparing(CompatibilityFinding::key)).toList();
	}

	public static List<CompatibilityFinding> confirmedRequired() {
		return all().stream().filter(CompatibilityFinding::confirmedRequired).toList();
	}

	/**
	 * Confirmed findings whose owner is no catalogue row: the kernel's own ({@code forbric}) and a mixin config no
	 * single mod claims ({@code config:<name>}). {@link #project} can attach nothing to them and the catalogue
	 * must never invent a row, so every player-facing list that reads the catalogue has to read these as well --
	 * otherwise the gate counts a loss the Mods screen and the text report never show.
	 */
	public static List<CompatibilityFinding> unattributed() {
		java.util.Set<String> rows = new java.util.HashSet<>();
		for (ModCatalog.Entry entry : ModCatalog.everything()) rows.add(entry.modId());
		return all().stream().filter(f -> f.confidence() == CompatibilityFinding.Confidence.CONFIRMED
				&& !rows.contains(f.modId())).toList();
	}

	/** What was noticed and not proved. Shown as notes; it never marks a mod, prompts or blocks a gate. */
	public static List<CompatibilityFinding> suspected() {
		return all().stream().filter(f -> f.confidence() == CompatibilityFinding.Confidence.SUSPECTED).toList();
	}

	/** Display is a projection: resolving a finding removes only its own reason, not unrelated failures. */
	static List<ModCatalog.Entry> project(List<ModCatalog.Entry> entries) {
		List<CompatibilityFinding> confirmed = all().stream()
				.filter(f -> f.confidence() == CompatibilityFinding.Confidence.CONFIRMED).toList();
		if (confirmed.isEmpty()) return entries;
		List<ModCatalog.Entry> result = new ArrayList<>(entries.size());
		for (ModCatalog.Entry entry : entries) {
			List<String> reasons = new ArrayList<>();
			if (!entry.statusDetail().isEmpty()) reasons.addAll(List.of(entry.statusDetail().split("; ")));
			for (CompatibilityFinding f : confirmed) {
				if (entry.modId().equals(f.modId()) && !reasons.contains(f.detail())) reasons.add(f.detail());
			}
			boolean affected = confirmed.stream().anyMatch(f -> entry.modId().equals(f.modId()));
			result.add(affected ? entry.withStatus(entry.status() == ModCatalog.Status.FAILED
					? ModCatalog.Status.FAILED : ModCatalog.Status.DEGRADED, String.join("; ", reasons)) : entry);
		}
		return List.copyOf(result);
	}

	/** Called once at the beginning of a new loader session; tests use the same boundary. */
	public static synchronized void reset() {
		FINDINGS.clear();
		revision++;
	}

	/** Cheap notification for the client tick; unchanged evidence does not move it or require another snapshot. */
	public static long revision() { return revision; }

	/** Stable machine report. Player acknowledgement deliberately does not change the release verdict. */
	public static String toJson() {
		List<CompatibilityFinding> findings = all();
		StringBuilder out = new StringBuilder("{\"schemaVersion\":1,\"confirmedRequired\":")
				.append(findings.stream().filter(CompatibilityFinding::confirmedRequired).count()).append(",\"findings\":[");
		for (int i = 0; i < findings.size(); i++) {
			CompatibilityFinding f = findings.get(i);
			if (i != 0) out.append(',');
			out.append("{\"id\":").append(json(f.id())).append(",\"modId\":").append(json(f.modId()))
					.append(",\"feature\":").append(json(f.feature())).append(",\"source\":").append(json(f.source()))
					.append(",\"confidence\":").append(json(f.confidence().name())).append(",\"required\":").append(f.required())
					.append(",\"detail\":").append(json(f.detail())).append(",\"evidence\":[");
			for (int j = 0; j < f.evidence().size(); j++) {
				if (j != 0) out.append(',');
				out.append(json(f.evidence().get(j)));
			}
			out.append("]}");
		}
		out.append("],\"catalogFailures\":[");
		List<ModCatalog.Entry> unclassified = ModCatalog.unclassifiedFailures().stream().filter(entry -> {
			if (entry.status() != ModCatalog.Status.FAILED) return true;
			List<CompatibilityFinding> observed = findings.stream().filter(f -> f.modId().equals(entry.modId())
					&& f.id().startsWith("initialization:") && f.confirmedRequired()).toList();
			if (observed.stream().anyMatch(f -> f.detail().equals(entry.statusDetail()))) return false;
			return java.util.Arrays.stream(entry.statusDetail().split("; "))
					.anyMatch(reason -> observed.stream().noneMatch(f -> f.detail().equals(reason)));
		}).toList();
		for (int i = 0; i < unclassified.size(); i++) {
			ModCatalog.Entry entry = unclassified.get(i);
			if (i != 0) out.append(',');
			out.append("{\"modId\":").append(json(entry.modId())).append(",\"name\":").append(json(entry.name()))
					.append(",\"status\":").append(json(entry.status().name())).append(",\"detail\":")
					.append(json(entry.statusDetail())).append(",\"classification\":\"UNCLASSIFIED\"}");
		}
		out.append("],\"mods\":[");
		List<ModCatalog.Entry> mods = ModCatalog.everything();
		for (int i = 0; i < mods.size(); i++) {
			ModCatalog.Entry entry = mods.get(i);
			if (i != 0) out.append(',');
			out.append("{\"modId\":").append(json(entry.modId())).append(",\"version\":").append(json(entry.version()))
					.append(",\"ecosystem\":").append(json(entry.ecosystem().name())).append(",\"jar\":").append(json(entry.jar()))
					.append(",\"bundledBy\":").append(json(entry.bundledBy())).append(",\"status\":").append(json(entry.status().name())).append('}');
		}
		return out.append("]}\n").toString();
	}

	private static String json(String value) {
		StringBuilder out = new StringBuilder("\"");
		for (char ch : value.toCharArray()) {
			switch (ch) {
				case '\\' -> out.append("\\\\");
				case '"' -> out.append("\\\"");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> { if (ch < 32) out.append(String.format("\\u%04x", (int) ch)); else out.append(ch); }
			}
		}
		return out.append('"').toString();
	}
}
