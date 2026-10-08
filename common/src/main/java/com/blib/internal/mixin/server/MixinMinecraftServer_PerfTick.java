package com.blib.internal.mixin.server;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

import com.blib.internal.common.perf.BLibPerfProfiler;

/**
 * Measures the whole server tick for {@code /blib perf} - the baseline every percentage in its report is taken against
 * - and ends a session when its window is full. tickServer is the work part of a tick only; the sleep that pads a fast
 * tick out to 50 ms happens outside it, so the figure is true MSPT.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer_PerfTick {

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void blib$perfTickServerHead(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.onServerTickStart();
        }
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void blib$perfTickServerReturn(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            BLibPerfProfiler.onServerTickEnd();
        }
    }
}
