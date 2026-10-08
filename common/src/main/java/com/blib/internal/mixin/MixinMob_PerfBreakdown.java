package com.blib.internal.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.blib.internal.common.perf.BLibPerfProfiler;

/**
 * Oct 5 - splits a mob's vanilla AI step for {@code /blib perf}'s "where the time goes" table: sensing and the target
 * and goal selectors, then path following, then the mob's own customServerAiStep, then the move/look/jump controls.
 * <p>
 * The boundaries are the calls Mob.serverAiStep makes, in the order they appear in its 1.21.1 bytecode (checked):
 * sensing, target and goal selectors, {@code navigation.tick()}, {@code customServerAiStep()}, then
 * {@code moveControl.tick()} and the look and jump controls. Every injection is optional ({@code require = 0}): a
 * profiler must never be the reason the game will not start.
 * </p>
 * <p>
 * Costs one static boolean read per boundary while no session runs.
 * </p>
 */
@Mixin(Mob.class)
public abstract class MixinMob_PerfBreakdown {

    @Inject(method = "serverAiStep", at = @At("HEAD"), require = 0)
    private void blib$perfGoalsStart(CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.markSection((Mob) (Object) this, BLibPerfProfiler.SECTION_GOALS);
        }
    }

    @Inject(
        method = "serverAiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;tick()V"
        ),
        require = 0
    )
    private void blib$perfNavStart(CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.markSection((Mob) (Object) this, BLibPerfProfiler.SECTION_NAV);
        }
    }

    @Inject(
        method = "serverAiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;customServerAiStep()V"),
        require = 0
    )
    private void blib$perfStepStart(CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.markSection((Mob) (Object) this, BLibPerfProfiler.SECTION_MOB_STEP);
        }
    }

    @Inject(
        method = "serverAiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/control/MoveControl;tick()V"),
        require = 0
    )
    private void blib$perfControlsStart(CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.markSection((Mob) (Object) this, BLibPerfProfiler.SECTION_CONTROLS);
        }
    }

    @Inject(method = "serverAiStep", at = @At("RETURN"), require = 0)
    private void blib$perfAiStepEnd(CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.markSection((Mob) (Object) this, -1);
        }
    }
}
