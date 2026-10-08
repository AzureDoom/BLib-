package com.blib.internal.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.blib.internal.common.perf.BLibPerfProfiler;

/**
 * Oct 5 - profiler v3: times vanilla's {@code LivingEntity.baseTick} (fluid pushing, fire, air, portals, effects) as
 * its own "base" column, so it no longer hides in "other". A subclass that overrides baseTick and does work around its
 * super call has that extra work counted in "other". Optional injections; one static read each when idle.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity_PerfBaseTick {

    @Inject(method = "baseTick", at = @At("HEAD"), require = 0)
    private void blib$perfBaseStart(CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.markSection((LivingEntity) (Object) this, BLibPerfProfiler.SECTION_BASE);
        }
    }

    @Inject(method = "baseTick", at = @At("RETURN"), require = 0)
    private void blib$perfBaseEnd(CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.markSection((LivingEntity) (Object) this, -1);
        }
    }
}
