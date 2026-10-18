package com.blib.api.common.pathfinding.v1.movement;

import net.minecraft.world.entity.PathfinderMob;

import java.util.Map;
import java.util.WeakHashMap;

import com.blib.api.common.pathfinding.v1.navigator.PathNavigatorApi;

/**
 * Carries out a leap the pathfinder planned (see PathLeapConfig): when the mob reaches the take-off of a leap step, it
 * is launched with exactly the impulse that lands it on the planned cell.
 * <h2>The impulse is simulated, not guessed</h2> Minecraft flight per tick is {@code y += vy; vy = (vy - 0.08) * 0.98}
 * and, horizontally in the air, {@code x += vx; vx *= 0.91}. The upward impulse is fixed (it clears a one-block-higher
 * landing with room to spare); the flight is run forward until the body comes back down to the landing height, and the
 * horizontal impulse is whatever covers the remaining distance over exactly that many ticks.
 * <h2>Launched once</h2> Only from the ground, level with the take-off and lined up with the landing, and the same leap
 * step never twice within {@link #RELAUNCH_TICKS} - so a mob that falls short into the gap does not leap again from the
 * pit floor (the level check alone already stops that), while a second leap right after the first launches at once.
 */
public final class PathLeap {

    /** Upward impulse. Peaks 1.84 blocks above the take-off (vanilla's own jump is 0.42 and peaks at 1.25). */
    public static final double LAUNCH_VY = 0.52;

    /** Horizontal speed cap, blocks per tick: enough for a 9-block leap, never a cannon shot. */
    private static final double MAX_HORIZONTAL = 1.4;

    private static final double GRAVITY = 0.08;

    private static final double VERTICAL_DRAG = 0.98;

    private static final double AIR_FRICTION = 0.91;

    /** How far (blocks) the mob may be before the take-off centre, or past it, along the leap and still launch. */
    private static final double TAKEOFF_BEFORE = 0.2;

    private static final double TAKEOFF_PAST = 0.9;

    /** How far off the line of the leap it may stand and still launch. */
    private static final double TAKEOFF_LATERAL = 0.6;

    /**
     * How long the SAME leap step may not be launched again. ⚠⚠ Per step, not per mob: two gaps in a row are an
     * ordinary route, and a mob-wide cooldown refused the second take-off a second after the first landing - so it
     * walked straight off the edge. A different leap launches at once.
     */
    private static final int RELAUNCH_TICKS = 40;

    private record Launch(
        com.blib.api.common.pathfinding.v1.node.PathNode node,
        int tick
    ) {}

    /** ⚠ Weak keys: a mob that dies or unloads mid-leap is forgotten, never kept alive by this map. */
    private static final Map<PathfinderMob, Launch> LAST_LAUNCH = new WeakHashMap<>();

    private PathLeap() {
        throw new UnsupportedOperationException();
    }

    /**
     * Launches the mob if its current path step is a leap and it stands at the take-off. Server side, once per tick.
     */
    static void tryLaunch(PathfinderMob actor, PathNavigatorApi navigator) {
        if (actor.level().isClientSide || !actor.onGround()) {
            return;
        }

        var node = navigator.getState().getCurrentNode();

        if (node == null || !node.isLeap()) {
            return;
        }

        var last = LAST_LAUNCH.get(actor);

        if (last != null && last.node() == node && actor.tickCount - last.tick() < RELAUNCH_TICKS) {
            return;
        }

        var takeoffX = node.getLeapTakeoffX();
        var takeoffZ = node.getLeapTakeoffZ();

        if (Math.abs(actor.getY() - node.getLeapTakeoffY()) > 0.6) {
            return; // not on the take-off's level - fell short, or not there yet
        }

        var landingX = node.getX() + 0.5;
        var landingZ = node.getZ() + 0.5;
        var lineX = landingX - takeoffX;
        var lineZ = landingZ - takeoffZ;
        var lineLength = Math.sqrt(lineX * lineX + lineZ * lineZ);

        if (lineLength < 1.0e-3) {
            return;
        }

        var dirX = lineX / lineLength;
        var dirZ = lineZ / lineLength;
        var offX = actor.getX() - takeoffX;
        var offZ = actor.getZ() - takeoffZ;
        var along = offX * dirX + offZ * dirZ;
        var lateral = Math.abs(offX * dirZ - offZ * dirX);

        if (along < -TAKEOFF_BEFORE || along > TAKEOFF_PAST || lateral > TAKEOFF_LATERAL) {
            return;
        }

        var toX = landingX - actor.getX();
        var toZ = landingZ - actor.getZ();
        var horizontal = Math.sqrt(toX * toX + toZ * toZ);
        var impulse = impulseFor(horizontal, node.getY() - actor.getY());

        actor.setDeltaMovement(toX / horizontal * impulse, LAUNCH_VY, toZ / horizontal * impulse);
        actor.hasImpulse = true;
        actor.setOnGround(false);
        LAST_LAUNCH.put(actor, new Launch(node, actor.tickCount));
    }

    /**
     * {@return the horizontal launch speed that covers {@code distance} blocks while the body rises and comes back down
     * to {@code rise} above (or below, if negative) the take-off}
     */
    public static double impulseFor(double distance, double rise) {
        var y = 0.0;
        var vy = LAUNCH_VY;
        var reach = 0.0;
        var factor = 1.0;

        for (var tick = 0; tick < 200; tick++) {
            y += vy;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            reach += factor;
            factor *= AIR_FRICTION;

            if (vy < 0.0 && y <= rise) {
                break;
            }
        }

        return Math.min(MAX_HORIZONTAL, distance / Math.max(1.0e-3, reach));
    }
}
