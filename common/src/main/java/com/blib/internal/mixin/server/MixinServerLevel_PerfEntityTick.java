package com.blib.internal.mixin.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.blib.internal.common.perf.BLibPerfProfiler;

/**
 * Times every entity tick for {@code /blib perf}, and tells the profiler which entity is ticking so work done inside
 * that tick (path searches) is charged to it.
 * <p>
 * ⚠ BOTH entry points: vehicles tick through tickNonPassenger and their riders through tickPassenger, which runs INSIDE
 * the vehicle's call. The profiler keeps a stack and pauses the vehicle's clock while a rider ticks, so each is charged
 * only for itself - a xenomorph riding a Create contraption is not billed to the contraption.
 * </p>
 * <p>
 * Costs one static boolean read per entity tick while no session runs.
 * </p>
 */
@Mixin(ServerLevel.class)
public abstract class MixinServerLevel_PerfEntityTick {

    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void blib$perfTickNonPassengerHead(Entity entity, CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.pushEntity(entity);
        }
    }

    @Inject(method = "tickNonPassenger", at = @At("RETURN"))
    private void blib$perfTickNonPassengerReturn(Entity entity, CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.popEntity(entity);
        }
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"))
    private void blib$perfTickPassengerHead(Entity vehicle, Entity passenger, CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.pushEntity(passenger);
        }
    }

    @Inject(method = "tickPassenger", at = @At("RETURN"))
    private void blib$perfTickPassengerReturn(Entity vehicle, Entity passenger, CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.popEntity(passenger);
        }
    }
}
