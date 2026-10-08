package com.blib.api.common.goap.v1.action.impl;

import com.just.ai.goap.StateKey;
import com.just.ai.goap.action.Action;
import com.just.ai.goap.state.Blackboard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;

import com.blib.api.common.entity.v1.EntityUtil;
import com.blib.mod.common.registry.init.BLibGameRules;

public class MoveToPosAction {

    private static final StateKey<Integer> TICKS_UNTIL_NEXT_PATH_RECALCULATION = StateKey.sensed("ticks_until_next_path_recalculation");

    private static final StateKey<BlockPos> LAST_OPENED_DOOR_POS = StateKey.sensed("last_opened_door_pos");

    /**
     * Within this horizontal distance of the destination (and {@link #ARRIVAL_VERTICAL} vertically) a mob has arrived.
     */
    private static final double ARRIVAL_HORIZONTAL = 1.0;

    private static final double ARRIVAL_VERTICAL = 1.5;

    /** How long a mob rests before searching a destination that just failed again, in ticks. */
    private static final int FAILED_DESTINATION_REST_TICKS = 30;

    /**
     * Destinations that just failed, per mob. ⭐ Needed because the fix makes failures REPORT: callers answer NO_PATH by
     * aborting, the planner picks the same move again at once, and its fresh action blackboard has no re-path cooldown
     * - so without this the same impossible search would run every tick. Weak keys: a removed mob, or a closed world's
     * mobs, are collected with nothing to clean up. Server thread only.
     */
    private static final java.util.Map<PathfinderMob, FailedDestination> FAILED_DESTINATIONS = new java.util.WeakHashMap<>();

    /**
     * Moves the actor toward {@code targetPos}.
     *
     * @param context         the action context
     * @param targetPos       where to go
     * @param speedMultiplier the speed
     * @return FINISHED when the actor has arrived, MOVING while it is on its way, NO_PATH when the destination cannot
     *         be reached from here (with {@code blibMoveToPosResultFix} on; see
     *         {@link BLibGameRules#MOVE_TO_POS_RESULT_FIX})
     */
    public static Result perform(
        Action.Context<? extends PathfinderMob> context,
        Vec3 targetPos,
        double speedMultiplier
    ) {
        if (!context.getActor().level().getGameRules().getBoolean(BLibGameRules.MOVE_TO_POS_RESULT_FIX)) {
            return performLegacy(context, targetPos, speedMultiplier);
        }

        var mob = context.getActor();
        var blackboard = context.getBlackboard(Blackboard.Scope.ACTION);
        var navigation = mob.getNavigation();
        var ticksUntilNextPathRecalculation = Math.max(blackboard.getOrDefault(TICKS_UNTIL_NEXT_PATH_RECALCULATION, 0) - 1, 0);
        blackboard.set(TICKS_UNTIL_NEXT_PATH_RECALCULATION, ticksUntilNextPathRecalculation);

        handleDoorInteractions(mob, blackboard);

        // Arrival is reported whenever it is true, not only on a re-path tick: vanilla hands back a one-node path to a
        // mob already standing on its destination, so the old code said MOVING to an arrived mob indefinitely.
        if (hasArrived(mob, targetPos)) {
            return Result.FINISHED;
        }

        if (ticksUntilNextPathRecalculation > 0) {
            return Result.MOVING;
        }

        ticksUntilNextPathRecalculation = 4 + mob.getRandom().nextInt(7);
        var distanceSqr = mob.distanceToSqr(targetPos);

        if (distanceSqr > 1024.0) {
            ticksUntilNextPathRecalculation += 10;
        } else if (distanceSqr > 256.0) {
            ticksUntilNextPathRecalculation += 5;
        }

        var now = mob.level().getGameTime();
        var failed = FAILED_DESTINATIONS.get(mob);

        if (failed != null && now < failed.until && failed.target.distanceToSqr(targetPos) <= 2.0) {
            blackboard.set(TICKS_UNTIL_NEXT_PATH_RECALCULATION, ticksUntilNextPathRecalculation + 15);
            return Result.NO_PATH;
        }

        var moving = navigation.moveTo(targetPos.x, targetPos.y, targetPos.z, speedMultiplier);
        var path = navigation.getPath();

        // A missing path is a failure - vanilla clears its path when a search finds nothing, and the old code read the
        // idle navigator as "done". So is a partial path whose end the mob is already standing on: it can get no
        // closer from here, and would otherwise re-path in place as MOVING forever.
        if (!moving || path == null || (!path.canReach() && isAtPathEnd(mob, path))) {
            FAILED_DESTINATIONS.put(mob, new FailedDestination(targetPos, now + FAILED_DESTINATION_REST_TICKS));
            navigation.stop();
            blackboard.set(TICKS_UNTIL_NEXT_PATH_RECALCULATION, ticksUntilNextPathRecalculation + 15);
            return Result.NO_PATH;
        }

        FAILED_DESTINATIONS.remove(mob);
        blackboard.set(TICKS_UNTIL_NEXT_PATH_RECALCULATION, ticksUntilNextPathRecalculation);
        return Result.MOVING;
    }

    private static boolean hasArrived(PathfinderMob mob, Vec3 targetPos) {
        var dx = mob.getX() - targetPos.x;
        var dz = mob.getZ() - targetPos.z;

        return dx * dx + dz * dz <= ARRIVAL_HORIZONTAL * ARRIVAL_HORIZONTAL
            && Math.abs(mob.getY() - targetPos.y) <= ARRIVAL_VERTICAL;
    }

    private static boolean isAtPathEnd(PathfinderMob mob, net.minecraft.world.level.pathfinder.Path path) {
        var end = path.getEndNode();

        if (end == null || path.getNodeCount() <= 1) {
            return true;
        }

        var dx = mob.getX() - (end.x + 0.5);
        var dz = mob.getZ() - (end.z + 0.5);

        return dx * dx + dz * dz <= 1.5 * 1.5 && Math.abs(mob.getY() - end.y) <= ARRIVAL_VERTICAL;
    }

    /** The behaviour before {@code blibMoveToPosResultFix}, kept verbatim for the game rule's off position. */
    private static Result performLegacy(
        Action.Context<? extends PathfinderMob> context,
        Vec3 targetPos,
        double speedMultiplier
    ) {
        var pathfinderMob = context.getActor();
        var blackboard = context.getBlackboard(Blackboard.Scope.ACTION);
        var navigation = pathfinderMob.getNavigation();
        var ticksUntilNextPathRecalculation = blackboard.getOrDefault(TICKS_UNTIL_NEXT_PATH_RECALCULATION, 0);

        ticksUntilNextPathRecalculation = Math.max(ticksUntilNextPathRecalculation - 1, 0);

        blackboard.set(TICKS_UNTIL_NEXT_PATH_RECALCULATION, ticksUntilNextPathRecalculation);

        handleDoorInteractions(pathfinderMob, blackboard);

        if (ticksUntilNextPathRecalculation <= 0) {
            // Always set the path recompute time after the path creation is attempted.
            ticksUntilNextPathRecalculation = 4 + pathfinderMob.getRandom().nextInt(7);

            var distanceSqr = pathfinderMob.distanceToSqr(targetPos);

            // Penalize tick recalculation for longer distances.
            if (distanceSqr > 1024.0) {
                ticksUntilNextPathRecalculation += 10;
            } else if (distanceSqr > 256.0) {
                ticksUntilNextPathRecalculation += 5;
            }

            Result result;

            if (navigation.moveTo(targetPos.x, targetPos.y, targetPos.z, speedMultiplier)) {
                result = Result.MOVING;
            } else {
                if (pathfinderMob.getNavigation().isDone()) {
                    return Result.FINISHED;
                }

                // Penalize tick recalculation if the computed path was invalid.
                ticksUntilNextPathRecalculation += 15;
                result = Result.NO_PATH;
            }

            // Always set the ticks until next path recalculation.
            blackboard.set(TICKS_UNTIL_NEXT_PATH_RECALCULATION, ticksUntilNextPathRecalculation);

            return result;
        }

        return Result.MOVING;
    }

    public static void onFinish(Action.Context<? extends PathfinderMob> context) {
        context.getActor().getNavigation().stop();
    }

    private static void handleDoorInteractions(PathfinderMob pathfinderMob, Blackboard blackboard) {
        if (
            !(pathfinderMob.getNavigation() instanceof GroundPathNavigation groundNavigation)
                || !groundNavigation.canOpenDoors()
        ) {
            return;
        }

        var path = groundNavigation.getPath();

        if (path == null || path.isDone()) {
            closeDoorIfTracked(pathfinderMob, blackboard);
            return;
        }

        var level = pathfinderMob.level();
        var nextNodeIndex = path.getNextNodeIndex();

        // Open a closed door at the next node.
        if (nextNodeIndex < path.getNodeCount()) {
            var nextNode = path.getNode(nextNodeIndex);
            var nextPos = nextNode.asBlockPos();
            var nextState = level.getBlockState(nextPos);

            if (nextState.getBlock() instanceof DoorBlock doorBlock && !nextState.getValue(DoorBlock.OPEN)) {
                doorBlock.setOpen(pathfinderMob, level, nextState, nextPos, true);
                blackboard.set(LAST_OPENED_DOOR_POS, nextPos);
            }
        }

        // Close the door at the previous node if it was opened by this entity.
        if (nextNodeIndex > 0) {
            var prevNode = path.getNode(nextNodeIndex - 1);
            var prevPos = prevNode.asBlockPos();
            var lastOpenedDoorPos = blackboard.getOrDefault(LAST_OPENED_DOOR_POS, null);

            if (
                lastOpenedDoorPos != null && lastOpenedDoorPos.equals(prevPos)
                    && !pathfinderMob.blockPosition().equals(prevPos)
            ) {
                var prevState = level.getBlockState(prevPos);

                if (prevState.getBlock() instanceof DoorBlock doorBlock && prevState.getValue(DoorBlock.OPEN)) {
                    doorBlock.setOpen(pathfinderMob, level, prevState, prevPos, false);
                }

                blackboard.set(LAST_OPENED_DOOR_POS, null);
            }
        }
    }

    private static void closeDoorIfTracked(PathfinderMob pathfinderMob, Blackboard blackboard) {
        var lastOpenedDoorPos = blackboard.getOrDefault(LAST_OPENED_DOOR_POS, null);

        if (lastOpenedDoorPos == null) {
            return;
        }

        if (
            // If the door is further than 3 blocks away...
            pathfinderMob.blockPosition().distSqr(lastOpenedDoorPos) > 9
                // OR if we can't see the door...
                || !EntityUtil.canMobSeeBlock(pathfinderMob, lastOpenedDoorPos.getCenter())
        ) {
            // Then return early, we can't close the door.
            blackboard.set(LAST_OPENED_DOOR_POS, null);
            return;
        }

        var level = pathfinderMob.level();
        var doorState = level.getBlockState(lastOpenedDoorPos);

        if (doorState.getBlock() instanceof DoorBlock doorBlock && doorState.getValue(DoorBlock.OPEN)) {
            doorBlock.setOpen(pathfinderMob, level, doorState, lastOpenedDoorPos, false);
        }

        blackboard.set(LAST_OPENED_DOOR_POS, null);
    }

    private MoveToPosAction() {
        throw new UnsupportedOperationException();
    }

    public enum Result {
        FINISHED,
        MOVING,
        NO_PATH,
    }

    private record FailedDestination(
        Vec3 target,
        long until
    ) {}
}
