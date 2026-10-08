package com.blib.internal.mixin.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.blib.internal.common.perf.BLibPerfProfiler;

/**
 * Oct 7 - times every ticking block entity for {@code /blib perf}, by block entity type.
 * <p>
 * A tester's report showed a 47.6 ms server tick with only 31.8 ms inside entities, and nothing to say where the other
 * 15.8 went. Machines, generators, pipes and chests are all block entities, so as the power and pipe systems grow this
 * is where their cost will show up.
 * </p>
 * <p>
 * {@code TickingBlockEntity.getType()} already returns the type's registry name, so no field access is needed. A wrap,
 * not a redirect, so it composes with other mods touching the same call; {@code require = 0} so a mod that rewrites the
 * whole loop (some performance mods do) only loses this table instead of failing to start. Costs one static boolean
 * read per block entity tick while no session runs.
 * </p>
 */
@Mixin(Level.class)
public abstract class MixinLevel_PerfBlockEntityTick {

    @WrapOperation(
        method = "tickBlockEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"
        ),
        require = 0
    )
    private void blib$perfTimeBlockEntity(TickingBlockEntity ticker, Operation<Void> original) {
        if (!BLibPerfProfiler.isActive() || ((Level) (Object) this).isClientSide()) {
            original.call(ticker);
            return;
        }

        var start = System.nanoTime();

        try {
            original.call(ticker);
        } finally {
            BLibPerfProfiler.recordBlockEntity(ticker.getType(), ticker.getPos().asLong(), System.nanoTime() - start);
        }
    }
}
