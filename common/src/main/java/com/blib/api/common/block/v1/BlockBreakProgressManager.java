package com.blib.api.common.block.v1;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class BlockBreakProgressManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockBreakProgressManager.class);

    private static final Map<BlockPos, Map.Entry<Long, Float>> BLOCK_BREAK_PROGRESS_MAP = new ConcurrentHashMap<>();

    public static void tick(Level level) {
        var gameTime = level.getGameTime();

        if (gameTime % (20 * 20) != 0) {
            return;
        }

        // LOGGER.debug(
        // "Cleaning block break progress map ({} entries)",
        // BlockBreakProgressManager.BLOCK_BREAK_PROGRESS_MAP.size()
        // );
        BlockBreakProgressManager.BLOCK_BREAK_PROGRESS_MAP.entrySet().removeIf(entry -> {
            var lastUpdateTimeMillis = entry.getValue().getKey();
            return System.currentTimeMillis() > lastUpdateTimeMillis;
        });
        // LOGGER.debug(
        // "Finished cleaning block break progress map ({} entries)",
        // BlockBreakProgressManager.BLOCK_BREAK_PROGRESS_MAP.size()
        // );
    }

    /**
     * Clears BLib's break progress at {@code pos} and tells nearby clients - but ONLY if BLib was actually tracking
     * progress there. Returns true if it was.
     * <p>
     * ⚠⚠ Added Sep 28 (Spark profile, BLib + Sable test pack). {@code MixinServerLevel_BlockBreakProgressManager} runs
     * on EVERY block change in the world - fluids settling in new chunks, leaves decaying, crops, pistons, and whole
     * contraptions being assembled or moved (Create/Aeronautics/Sable). It used to call {@link #resetProgress}, which
     * always calls {@code ServerLevel.destroyBlockProgress}: vanilla walks every player and sends a block-destruction
     * packet to each one within 32 blocks. So every block change near a player cost a network packet per nearby player,
     * even though BLib had no break progress there to clear (0.39% of the server thread in a quiet test world, before
     * any contraption moved). This skips all of that when nothing is tracked - which is almost always.
     */
    public static boolean clearProgressIfTracked(Level level, BlockPos pos) {
        if (BlockBreakProgressManager.BLOCK_BREAK_PROGRESS_MAP.remove(pos.immutable()) == null) {
            return false;
        }

        level.destroyBlockProgress(computeBlockPosHash(pos), pos, -1);
        return true;
    }

    /**
     * Clears the break progress at {@code pos} and ALWAYS tells nearby clients, tracked or not. Kept exactly as before
     * for callers that break blocks themselves; the per-block-change hook uses {@link #clearProgressIfTracked}.
     */
    public static void resetProgress(Level level, BlockPos pos) {
        BlockBreakProgressManager.BLOCK_BREAK_PROGRESS_MAP.remove(pos.immutable());
        level.destroyBlockProgress(computeBlockPosHash(pos), pos, -1);
    }

    public static void setProgress(Level level, BlockPos pos, float progress) {
        var newEntry = Map.entry(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5), progress);
        BlockBreakProgressManager.BLOCK_BREAK_PROGRESS_MAP.put(pos.immutable(), newEntry);

        var clampedProgress = getClampedProgress(progress);
        level.destroyBlockProgress(computeBlockPosHash(pos), pos, clampedProgress);
    }

    public static float getProgress(BlockPos pos) {
        var entry = BlockBreakProgressManager.BLOCK_BREAK_PROGRESS_MAP.get(pos.immutable());

        return entry == null ? 0.0f : entry.getValue();
    }

    // Damage progress is a value ranging from 0 to 9 (both ends inclusively).
    // All blocks also have a destruction time (in seconds).
    public static Result damage(Level level, BlockPos blockPos, float damage) {
        var immutableBlockPos = blockPos.immutable();

        var entry = BlockBreakProgressManager.BLOCK_BREAK_PROGRESS_MAP.get(immutableBlockPos);

        var blockState = level.getBlockState(immutableBlockPos);
        var block = blockState.getBlock();
        var currentDestroyProgress = entry == null ? 0 : entry.getValue();
        var defaultDestroyTimeInSeconds = block.defaultDestroyTime();

        if (defaultDestroyTimeInSeconds < 0 || blockState.is(Blocks.FIRE)) {
            // This block cannot be destroyed, so abort.
            return Result.NOT_DAMAGED;
        }

        var destroyTimeInTicks = block.defaultDestroyTime() * 20;
        var weight = Math.max(destroyTimeInTicks, 1);

        var newDestroyProgress = currentDestroyProgress + (damage / weight);

        if (newDestroyProgress >= 9) {
            resetProgress(level, immutableBlockPos);
            level.destroyBlock(immutableBlockPos, false);
            return Result.DESTROYED;
        }

        setProgress(level, immutableBlockPos, newDestroyProgress);

        return Result.DAMAGED;
    }

    private static int getClampedProgress(float progress) {
        return (int) Mth.clamp(progress, -1F, 9F);
    }

    private static int computeBlockPosHash(BlockPos pos) {
        return Objects.hash(pos);
    }

    private BlockBreakProgressManager() {
        throw new UnsupportedOperationException();
    }

    public enum Result {
        DAMAGED,
        DESTROYED,
        NOT_DAMAGED
    }
}
