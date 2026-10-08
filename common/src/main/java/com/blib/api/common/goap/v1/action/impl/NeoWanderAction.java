package com.blib.api.common.goap.v1.action.impl;

import com.just.ai.goap.StateKey;
import com.just.ai.goap.action.Action;
import com.just.ai.goap.state.Blackboard;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

import com.blib.api.common.pathfinding.v1.debug.PathDebugUtil;
import com.blib.api.common.pathfinding.v1.navigator.PathNavigatorUser;

/**
 * GOAP wander action using BLib's {@link com.blib.api.common.pathfinding.v1.navigator.PathNavigator}. Picks a random
 * land position and navigates to it. Falls back to {@link WanderAction} for entities that don't implement
 * {@link PathNavigatorUser}.
 */
public final class NeoWanderAction {

    private static final StateKey<Vec3> TARGET_POS = StateKey.sensed("neo_wander_target_pos");

    /** Oct 6 - game time until which a failed wander stands still instead of searching again. */
    private static final StateKey<Long> PAUSED_UNTIL = StateKey.sensed("neo_wander_paused_until");

    /** How long a failed wander stands before trying again: 2 seconds. */
    private static final long FAILURE_PAUSE_TICKS = 40L;

    public static <T extends PathfinderMob> Action.Signal perform(
        Action.Context<? extends T> context,
        int radius,
        int verticalRange,
        double speedMultiplier,
        Consumer<Action.Context<? extends T>> onWanderCompleteCallback
    ) {
        var actor = context.getActor();

        if (!(actor instanceof PathNavigatorUser)) {
            return WanderAction.perform(context, radius, verticalRange, speedMultiplier, onWanderCompleteCallback);
        }

        var blackboard = context.getBlackboard(Blackboard.Scope.ACTION);

        // ⭐ Oct 6 - A FAILED WANDER RESTS INSTEAD OF RETRYING EVERY TICK. Underground or boxed in, LandRandomPos often
        // finds nothing (ten random probes, each with a path-type evaluation) or the spot it finds cannot be reached.
        // Either way the action aborted, the planner chose wander again the very next tick, and it all ran again - 49
        // us
        // per yautja per tick in a cave fight by /blib perf. Now the mob stands for two seconds and then tries once
        // more.
        // It is still the running action, so anything more urgent interrupts it exactly as before.
        // Off: /gamerule blibWanderFailurePause false.
        var pauseEnabled = actor.level().getGameRules().getBoolean(com.blib.mod.common.registry.init.BLibGameRules.WANDER_FAILURE_PAUSE);

        if (pauseEnabled) {
            var pausedUntil = blackboard.getOrNull(PAUSED_UNTIL);

            if (pausedUntil != null) {
                if (actor.level().getGameTime() < pausedUntil) {
                    return Action.Signal.CONTINUE;
                }

                blackboard.set(PAUSED_UNTIL, null);
            }
        }

        var targetPosOrNull = blackboard.getOrNull(TARGET_POS);

        if (targetPosOrNull == null) {
            targetPosOrNull = LandRandomPos.getPos(actor, radius, verticalRange);

            if (targetPosOrNull == null) {
                return failed(actor, blackboard, pauseEnabled);
            }

            blackboard.set(TARGET_POS, targetPosOrNull);
        }

        return switch (NeoMoveToPosAction.perform(context, targetPosOrNull, speedMultiplier)) {
            case FINISHED -> {
                onWanderCompleteCallback.accept(context);
                yield Action.Signal.CONTINUE;
            }
            case MOVING -> Action.Signal.CONTINUE;
            case NO_PATH -> failed(actor, blackboard, pauseEnabled);
        };
    }

    /** No spot or no path: with the pause on, forget the spot and stand for a moment; off, abort as before. */
    private static Action.Signal failed(PathfinderMob actor, Blackboard blackboard, boolean pauseEnabled) {
        if (!pauseEnabled) {
            return Action.Signal.ABORT;
        }

        blackboard.set(TARGET_POS, null);
        blackboard.set(PAUSED_UNTIL, actor.level().getGameTime() + FAILURE_PAUSE_TICKS);

        if (actor instanceof PathNavigatorUser navigatorUser) {
            navigatorUser.getPathNavigator().stop();
        }

        return Action.Signal.CONTINUE;
    }

    public static void onFinish(Action.Context<? extends PathfinderMob> context) {
        if (context.getActor() instanceof PathNavigatorUser navigatorUser) {
            var navigator = navigatorUser.getPathNavigator();
            navigator.stop();
            PathDebugUtil.sendDebugSearchSnapshot(context.getActor(), navigator);
            PathDebugUtil.sendDebugNavState(context.getActor(), navigator);
        } else {
            context.getActor().getNavigation().stop();
        }
    }

    private NeoWanderAction() {
        throw new UnsupportedOperationException();
    }
}
