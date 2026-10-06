package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;

/**
 * An injector bound only to a merged-base method nothing in the merged game calls is reported as not running — the
 * shape of Better Mount HUD's XP redirect in {@code Hud.extractHotbarAndDecorations}, whose one vanilla caller NeoForge's
 * HUD layers replaced — with the shipped row, synthetic classes, and no staged jar.
 */
@ResourceLock("system-properties")
class MixinFitLivenessTest {
	private static final String HUD = "net/minecraft/client/gui/Hud";
	private static final String G = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V";
	private static final String HOTBAR = "extractHotbarAndDecorations";
	private static final String HAS_EXPERIENCE = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;hasExperience()Z";
	private static final String MIXIN = "test/HudMixin";

	@AfterEach
	void reset() {
		System.clearProperty(MixinFit.LIVENESS_PROPERTY);
		System.clearProperty(MixinRetarget.ORPHANED_PIECE_PROPERTY);
		MixinStubRebind.forget();
		MixinRetarget.reset();
		MergedBaseUncalledMethods.forgetGuests();
	}

	@Test
	void aRedirectBoundOnlyInAMethodNothingCallsIsPartialWithTheReason() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MixinFit.Result fit = MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false))));
		assertEquals(MixinFit.Verdict.PARTIAL, fit.verdict(), fit.unresolved().toString());
		assertEquals(1, fit.resolved(), "the @At member is still there");
		assertEquals(List.of("@Inject target Hud.extractHotbarAndDecorations never runs: nothing in the merged game calls it; "
				+ "vanilla and MinecraftForge call it from Hud.extractRenderState"), fit.unresolved());
		assertTrue(!fit.shouldSuppress(), "soft: reported, never dropped");
	}

	@Test
	void theSwitchReadsItAsResolvedAgain() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		System.setProperty(MixinFit.LIVENESS_PROPERTY, "off");
		MixinFit.Result fit = MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false))));
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.unresolved().toString());
	}

	/**
	 * The binding still counts against UNFIT: malilib's stub-bound hook (MixinStubRebind off) is bound to a stub nothing
	 * calls and misses its @At there; it must stay PARTIAL and kept, not become UNFIT and be dropped.
	 */
	@Test
	void aNeverRunningBindingNeverMakesAMixinUnfit() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MixinFit.Result fit = MixinFit.evaluate(redirect(HUD, HOTBAR, "Lnet/example/Gone;gone()Z"), resolver(Map.of(HUD, hud(false))));
		assertEquals(MixinFit.Verdict.PARTIAL, fit.verdict(), fit.unresolved().toString());
		assertEquals(2, fit.unresolved().size(), fit.unresolved().toString());
		assertTrue(!fit.shouldSuppress());
	}

	/** A transform that restores the call in the method's own class makes it live: the row is re-checked on the live bytes. */
	@Test
	void aCallBackInTheLiveClassMakesItRun() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MixinFit.Result fit = MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(true))));
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.unresolved().toString());
	}

	/**
	 * An installed mod calling the method makes it run: fabric-resource-loader, Iris and Collective call the
	 * {@code Language.loadFromJson} stub the merged game no longer does. The boot-time scan reads it from the constant pool.
	 */
	@Test
	void aModThatCallsTheMethodMakesItRun() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MergedBaseUncalledMethods.noteGuest(type("test/Caller", "draw", "()V", HUD, HOTBAR, G, false));
		assertTrue(MergedBaseUncalledMethods.calledByGuest(HOTBAR + G));
		MixinFit.Result fit = MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false))));
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.unresolved().toString());
	}

	/** NeoForge's own game never had the method, so a NeoForge mod was not promised a caller; nor is a mod nobody owns. */
	@Test
	void onlyTheEcosystemsWhoseGameCallsItAreJudged() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE);
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false)))).verdict());
		MixinStubRebind.forget();
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false)))).verdict());
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolver(Map.of(HUD, hud(false)))).verdict());
	}

	/**
	 * The experience redirect follows {@code hasExperience} into {@code extractExperienceLevel}, the method the HUD
	 * layer actually calls. The copy in {@code extractHotbarAndDecorations} stays uncalled.
	 */
	@Test
	void theExperienceRedirectFollowsTheLiveLayer() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] mixin = redirect(HUD, HOTBAR, HAS_EXPERIENCE);
		Function<String, byte[]> resolved = resolver(Map.of(HUD, hudWithExperienceLayer(true)));
		MixinFit.Result before = MixinFit.evaluate(mixin, resolved);
		assertEquals(MixinFit.Verdict.PARTIAL, before.verdict(), before.unresolved().toString());
		MixinRetarget.Adoption adoption = MixinRetarget.adopt(mixin, before, resolved, b -> MixinFit.evaluate(b, resolved));
		assertTrue(adoption != null, "the reviewed piece should be taken");
		assertEquals(1, adoption.plan().rewrites().size(), adoption.plan().describe());
		assertEquals("extractExperienceLevel" + G, adoption.plan().rewrites().get(0).to());
		assertEquals(MixinFit.Verdict.FIT, adoption.after().verdict(), adoption.after().unresolved().toString());
	}

	/** Nothing calls the live method either, so the redirect is not moved onto another method that never runs. */
	@Test
	void anUnreferencedPieceIsLeftAlone() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] mixin = redirect(HUD, HOTBAR, HAS_EXPERIENCE);
		Function<String, byte[]> resolved = resolver(Map.of(HUD, hudWithExperienceLayer(false)));
		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolved);
		assertEquals(0, plan.rewrites().size(), plan.describe());
	}

	/** The old method running again means the redirect already fires; it stays where the mod put it. */
	@Test
	void aCallerOfTheOldMethodKeepsTheRedirectThere() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] hud = hudWithExperienceLayer(true);
		// extractRenderState calls the old method, which the shipped row then reads as live.
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		new org.objectweb.asm.ClassReader(hud).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (!"extractRenderState".equals(name)) return mv;
				return new MethodVisitor(Opcodes.ASM9, mv) {
					@Override
					public void visitInsn(int opcode) {
						if (opcode == Opcodes.RETURN) {
							super.visitVarInsn(Opcodes.ALOAD, 0);
							super.visitVarInsn(Opcodes.ALOAD, 1);
							super.visitVarInsn(Opcodes.ALOAD, 2);
							super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HUD, HOTBAR, G, false);
						}
						super.visitInsn(opcode);
					}
				};
			}
		}, 0);
		Function<String, byte[]> resolved = resolver(Map.of(HUD, cw.toByteArray()));
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(redirect(HUD, HOTBAR, HAS_EXPERIENCE), resolved).verdict());
		assertEquals(0, MixinRetarget.plan(MixinFit.parse(redirect(HUD, HOTBAR, HAS_EXPERIENCE)), resolved).rewrites().size());
	}

	@Test
	void thePieceMoveCanBeSwitchedOff() {
		System.setProperty(MixinRetarget.ORPHANED_PIECE_PROPERTY, "off");
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] mixin = redirect(HUD, HOTBAR, HAS_EXPERIENCE);
		Function<String, byte[]> resolved = resolver(Map.of(HUD, hudWithExperienceLayer(true)));
		assertEquals(0, MixinRetarget.plan(MixinFit.parse(mixin), resolved).rewrites().size());
		assertEquals(null, MixinRetarget.adopt(mixin, MixinFit.evaluate(mixin, resolved), resolved,
				b -> MixinFit.evaluate(b, resolved)));
	}

	/** A NeoForge mod was compiled against the layer. The row is for the games that still name the old method. */
	@Test
	void aNeoForgeModIsNotMovedOntoThePiece() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE);
		byte[] mixin = redirect(HUD, HOTBAR, HAS_EXPERIENCE);
		Function<String, byte[]> resolved = resolver(Map.of(HUD, hudWithExperienceLayer(true)));
		assertEquals(0, MixinRetarget.plan(MixinFit.parse(mixin), resolved).rewrites().size());
	}

	/** A listed caller in another class is read through the resolver: vanilla's fluid renderer asking the fluid's tint. */
	@Test
	void aListedCallerInAnotherClassIsReadThroughTheResolver() {
		String model = "net/minecraft/client/renderer/block/FluidModel";
		String renderer = "net/minecraft/client/renderer/block/FluidRenderer";
		String tint = "tintSource", tintDesc = "()Lnet/minecraft/client/color/block/BlockTintSource;";
		String tesselate = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;"
				+ "Lnet/minecraft/world/level/material/FluidState;)V";
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] mixin = redirect(model, tint, "Ljava/lang/Object;hashCode()I");
		byte[] target = type(model, tint, tintDesc, "java/lang/Object", "hashCode", "()I", false);

		MixinFit.Result dead = MixinFit.evaluate(mixin, resolver(Map.of(model, target,
				renderer, type(renderer, "tesselate", tesselate, null, null, null, false))));
		assertEquals(MixinFit.Verdict.PARTIAL, dead.verdict(), dead.unresolved().toString());
		assertTrue(dead.unresolved().get(0).endsWith("vanilla and MinecraftForge call it from FluidRenderer.tesselate"),
				dead.unresolved().toString());

		MixinFit.Result live = MixinFit.evaluate(mixin, resolver(Map.of(model, target,
				renderer, type(renderer, "tesselate", tesselate, model, tint, tintDesc, false))));
		assertEquals(MixinFit.Verdict.FIT, live.verdict(), live.unresolved().toString());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static Function<String, byte[]> resolver(Map<String, byte[]> classes) {
		return name -> classes.get(name.substring(0, name.length() - ".class".length()));
	}

	/** Hud with extractHotbarAndDecorations calling hasExperience, and extractRenderState — calling it back when asked. */
	private static byte[] hud(boolean renderCallsHotbar) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, HUD, null, "java/lang/Object", null);
		MethodVisitor hotbar = cw.visitMethod(Opcodes.ACC_PUBLIC, HOTBAR, G, null, null);
		hotbar.visitCode();
		hotbar.visitInsn(Opcodes.ACONST_NULL);
		hotbar.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/multiplayer/MultiPlayerGameMode", "hasExperience", "()Z", false);
		hotbar.visitInsn(Opcodes.POP);
		hotbar.visitInsn(Opcodes.RETURN);
		hotbar.visitMaxs(0, 0);
		hotbar.visitEnd();
		MethodVisitor render = cw.visitMethod(Opcodes.ACC_PUBLIC, "extractRenderState", G, null, null);
		render.visitCode();
		if (renderCallsHotbar) {
			render.visitVarInsn(Opcodes.ALOAD, 0);
			render.visitVarInsn(Opcodes.ALOAD, 1);
			render.visitVarInsn(Opcodes.ALOAD, 2);
			render.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HUD, HOTBAR, G, false);
		}
		render.visitInsn(Opcodes.RETURN);
		render.visitMaxs(0, 0);
		render.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** The dead copy, the live experience-level method, and — when asked — a layer registration that handles the live one. */
	private static byte[] hudWithExperienceLayer(boolean referenced) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, HUD, null, "java/lang/Object", null);
		experienceGate(cw, HOTBAR);
		experienceGate(cw, "extractExperienceLevel");
		MethodVisitor render = cw.visitMethod(Opcodes.ACC_PUBLIC, "extractRenderState", G, null, null);
		render.visitCode();
		render.visitInsn(Opcodes.RETURN);
		render.visitMaxs(0, 0);
		render.visitEnd();
		MethodVisitor layers = cw.visitMethod(Opcodes.ACC_PRIVATE, "registerVanillaLayers", "()V", null, null);
		layers.visitCode();
		if (referenced) {
			layers.visitLdcInsn(new Handle(Opcodes.H_INVOKEVIRTUAL, HUD, "extractExperienceLevel", G, false));
			layers.visitInsn(Opcodes.POP);
		}
		layers.visitInsn(Opcodes.RETURN);
		layers.visitMaxs(0, 0);
		layers.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void experienceGate(ClassWriter cw, String name) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, name, G, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/multiplayer/MultiPlayerGameMode", "hasExperience", "()Z", false);
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/** A class with one method, which calls {@code calleeOwner.callee} when one is given. */
	private static byte[] type(String name, String method, String desc, String calleeOwner, String callee, String calleeDesc,
			boolean isStatic) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), method, desc, null, null);
		mv.visitCode();
		if (callee != null) {
			mv.visitInsn(Opcodes.ACONST_NULL);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, calleeOwner, callee, calleeDesc, false);
			mv.visitInsn(Opcodes.POP);
		}
		if (desc.endsWith("V")) mv.visitInsn(Opcodes.RETURN);
		else { mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN); }
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code @Mixin(targets = target) class HudMixin { @Redirect(method = method, at = @At(value = "INVOKE", target = at)) … }} */
	private static byte[] redirect(String target, String method, String at) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target.replace('/', '.'));
		targets.visitEnd();
		mixin.visitEnd();
		MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "hide", "(Ljava/lang/Object;)Z", null, null);
		AnnotationVisitor redirect = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Redirect;", true);
		AnnotationVisitor methods = redirect.visitArray("method");
		methods.visit(null, method);
		methods.visitEnd();
		AnnotationVisitor point = redirect.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
		point.visit("value", "INVOKE");
		point.visit("target", at);
		point.visitEnd();
		redirect.visitEnd();
		handler.visitCode();
		handler.visitInsn(Opcodes.ICONST_0);
		handler.visitInsn(Opcodes.IRETURN);
		handler.visitMaxs(0, 0);
		handler.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
