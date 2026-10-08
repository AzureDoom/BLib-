package com.blib.internal.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.blib.internal.common.perf.BLibPerfProfiler;

/**
 * Oct 5 - times movement physics and collision ({@code LivingEntity.travel}) for {@code /blib perf}'s breakdown.
 * <p>
 * ⚠ A mob whose class overrides travel without calling super is not timed here; its movement lands in the "other"
 * column instead. Optional injections ({@code require = 0}), one static boolean read each while no session runs.
 * </p>
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity_PerfTravel {

    @Inject(method = "travel", at = @At("HEAD"), require = 0)
    private void blib$perfTravelStart(Vec3 travelVector, CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.markSection((LivingEntity) (Object) this, BLibPerfProfiler.SECTION_MOVE);
        }
    }

    @Inject(method = "travel", at = @At("RETURN"), require = 0)
    private void blib$perfTravelEnd(Vec3 travelVector, CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.markSection((LivingEntity) (Object) this, -1);
        }
    }
}
