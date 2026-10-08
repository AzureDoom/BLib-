package com.blib.api.common.pathfinding.v1.movement;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;

import com.blib.api.common.pathfinding.v1.navigator.PathNavigatorApi;

/**
 * Applies {@link PathNavigatorApi} waypoints to Minecraft mob movement controls.
 */
public final class PathMovementController {

    /** Vanilla's own ladder rise, blocks per tick. */
    private static final double CLIMB_RISE = 0.2;

    /** Blocks per tick of sideways push toward an off-column waypoint while on a climbable. */
    private static final double LEDGE_NUDGE = 0.06;

    /** How far off the column (blocks) a waypoint must be before the nudge applies. */
    private static final double LEDGE_NUDGE_MIN_OFFSET = 0.35;

    /**
     * Moves a mob toward the supplied navigator waypoint, applying terrain-specific execution behavior when needed.
     */
    public static void follow(
        PathfinderMob actor,
        PathNavigatorApi navigator,
        Vec3 waypointCenter,
        double speedMultiplier
    ) {
        var resolvedSpeedMultiplier = WaterPathMovementController.resolveSpeedMultiplier(
            actor,
            navigator,
            speedMultiplier
        );

        actor.getMoveControl()
            .setWantedPosition(
                waypointCenter.x,
                waypointCenter.y,
                waypointCenter.z,
                resolvedSpeedMultiplier
            );

        // ⚠⚠ CLIMBING IS VANILLA'S JOB; THIS ONLY PULLS THE TRIGGER. LivingEntity.travel lifts a body on a climbable
        // by 0.2 per tick while it is jumping or pressing into the wall. A mob whose next waypoint is straight up a
        // ladder is doing neither, so it hung on the bottom rung. Jumping is the clean trigger — no horizontal push,
        // no custom physics — and going DOWN needs nothing at all: the same code clamps the fall to 0.15 per tick.
        // ⚠⚠ THE BLOCK, NOT onClimbable(). Mods override onClimbable() for their own wall-climbing (the yautja does,
        // and so does vanilla's spider), and with that as the trigger every wall climb with a waypoint above it got
        // a jump() per tick ON TOP of its own climb physics — [tester] "they float upwards into space and then tp
        // back down". Only a block in minecraft:climbable under the body is a ladder, vine or scaffold.
        if (inClimbableBlock(actor)) {
            if (waypointCenter.y > actor.getY() + 0.5) {
                // ⚠ Both: the jump flag is what vanilla's travel keys the lift on, but it is only read on the tick it
                // is set, and this runs from the AI tick — so the lift is also written directly. Vanilla's climbable
                // clamp only bounds the fall, so a 0.2 rise survives it, and gravity takes its 0.08 as usual.
                actor.getJumpControl().jump();

                var motion = actor.getDeltaMovement();

                if (motion.y < CLIMB_RISE) {
                    actor.setDeltaMovement(motion.x, CLIMB_RISE, motion.z);
                }
            }

            // ⚠ THE LEDGE NUDGE. At the top rung the next waypoint is beside the column, one block up. Vanilla's
            // climbable clamp caps horizontal motion at 0.15/tick and the move control alone can leave a body hanging
            // on the lip; a small push toward the waypoint carries it over. Off-column only: a waypoint straight up
            // or down the shaft gets no sideways push at all.
            var dx = waypointCenter.x - actor.getX();
            var dz = waypointCenter.z - actor.getZ();
            var horizontal = Math.sqrt(dx * dx + dz * dz);

            if (horizontal > LEDGE_NUDGE_MIN_OFFSET && waypointCenter.y >= actor.getY() - 0.5) {
                var motion = actor.getDeltaMovement();

                actor.setDeltaMovement(
                    motion.x + dx / horizontal * LEDGE_NUDGE,
                    motion.y,
                    motion.z + dz / horizontal * LEDGE_NUDGE
                );
            }
        }

        WaterPathMovementController.apply(actor, navigator, waypointCenter, resolvedSpeedMultiplier);
    }

    private static boolean inClimbableBlock(PathfinderMob actor) {
        return actor.level().getBlockState(actor.blockPosition()).is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }

    private PathMovementController() {
        throw new UnsupportedOperationException();
    }
}
