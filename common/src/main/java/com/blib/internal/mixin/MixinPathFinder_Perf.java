package com.blib.internal.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

import com.blib.internal.common.perf.BLibPerfProfiler;

/**
 * Counts and times vanilla path searches for {@code /blib perf}.
 * <p>
 * ⭐ Marines use vanilla GroundPathNavigation, not BLib's navigator, so without this their path cost would be invisible
 * to the profiler - and every vanilla mob in the report gets a comparable figure for free. Vanilla does not expose how
 * many nodes a search visited, so only time, failures (no path) and partial paths (a path that cannot reach) are kept.
 * </p>
 * <p>
 * ⚠ The full descriptor is required: PathFinder has a private findPath overload with a different signature.
 * </p>
 */
@Mixin(PathFinder.class)
public abstract class MixinPathFinder_Perf {

    @Unique
    private long blib$perfSearchStartNanos;

    @Inject(
        method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At("HEAD")
    )
    private void blib$perfFindPathHead(
        PathNavigationRegion region,
        Mob mob,
        Set<BlockPos> targets,
        float maxRange,
        int accuracy,
        float searchDepthMultiplier,
        CallbackInfoReturnable<Path> cir
    ) {
        if (BLibPerfProfiler.isActive()) {
            blib$perfSearchStartNanos = System.nanoTime();
        }
    }

    @Inject(
        method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At("RETURN")
    )
    private void blib$perfFindPathReturn(
        PathNavigationRegion region,
        Mob mob,
        Set<BlockPos> targets,
        float maxRange,
        int accuracy,
        float searchDepthMultiplier,
        CallbackInfoReturnable<Path> cir
    ) {
        if (BLibPerfProfiler.isActive() && blib$perfSearchStartNanos != 0L) {
            var path = cir.getReturnValue();
            BLibPerfProfiler.recordVanillaPathSearch(
                mob,
                System.nanoTime() - blib$perfSearchStartNanos,
                path == null,
                path != null && !path.canReach()
            );
            blib$perfSearchStartNanos = 0L;
        }
    }
}
