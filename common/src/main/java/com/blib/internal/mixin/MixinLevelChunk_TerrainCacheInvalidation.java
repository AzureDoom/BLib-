package com.blib.internal.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.blib.api.common.pathfinding.v1.cache.TerrainCacheRegistry;

@Mixin(LevelChunk.class)
public abstract class MixinLevelChunk_TerrainCacheInvalidation {

    @Shadow
    public abstract Level getLevel();

    @Inject(at = @At("RETURN"), method = "setBlockState")
    private void onSetBlockState(BlockPos pos, BlockState state, boolean flag, CallbackInfoReturnable<BlockState> cir) {
        var previous = cir.getReturnValue();

        if (previous == null) {
            return;
        }

        // ⭐ Oct 6 - EVERY real change goes into the change log, BEFORE the same-shape shortcut below. The path
        // evaluator now keeps its lookup caches between searches and asks this log whether anything moved in its
        // area; those caches include block-break decisions, which a same-shape swap (stone to resin) DOES change.
        // Server levels only: the client never searches.
        if (!getLevel().isClientSide()) {
            com.blib.api.common.pathfinding.v1.cache.BlockChangeLog.record(getLevel(), pos);
        }

        // 🚨🚨 INVALIDATION IS ENORMOUSLY EXPENSIVE - ONE BLOCK NUKES A WHOLE 16^3 SECTION (4096 nodes), plus its
        // neighbours when the block sits on a boundary, and the next path request reclassifies every one of them.
        // This fires on EVERY setBlockState in the world, so a building hive was throwing away and rebuilding whole
        // sections continuously.
        //
        // ⭐ A CHANGE THAT CANNOT ALTER TRAVERSABILITY CANNOT ALTER A CLASSIFICATION. Same block, same collision
        // shape means every node in that section would classify exactly as it already has - so skip. This covers
        // pure state flips: a vent going dormant, a door being powered, a plant growing a stage.
        //
        // ⚠ REFERENCE EQUALITY ON THE SHAPE, ON PURPOSE. VoxelShapes are cached and shared per blockstate, so
        // identical shapes are usually the same instance; a false negative just invalidates as before, which is
        // always safe. Never the other way round.
        if (previous.getBlock() == state.getBlock()) {
            var level = getLevel();

            // ⚠⚠ THE FLUID MUST MATCH TOO, NOT JUST THE SHAPE. WATERLOGGING DOES NOT CHANGE A COLLISION SHAPE, and
            // classifiers routinely distinguish water from ground - BLib's own GROUND_AND_WATER does, and
            // avp_alien's xenomorph classifier tests liquid() directly. Comparing shape alone would let a node that
            // has just flooded keep its old "dry ground" classification until something else invalidated the
            // section, which is a routing bug, not a performance one.
            if (
                previous.getFluidState() == state.getFluidState()
                    && previous.getCollisionShape(level, pos) == state.getCollisionShape(level, pos)
            ) {
                return;
            }
        }

        TerrainCacheRegistry.onBlockChanged(getLevel(), pos);
    }
}
