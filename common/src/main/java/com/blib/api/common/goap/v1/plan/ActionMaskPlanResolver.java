package com.blib.api.common.goap.v1.plan;

import com.just.ai.goap.plan.Plan;
import com.just.ai.goap.plan.executor.impl.ConcurrentPlanExecutor;
import com.just.ai.goap.state.ReadableWorldState;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.blib.api.common.goap.v1.action.ActionMask;
import com.blib.api.common.goap.v1.action.BLibAction;

public class ActionMaskPlanResolver<T> implements ConcurrentPlanExecutor.PlanResolver<T> {

    @Override
    public Resolution resolve(Plan<T> plan, Plan<T> plan1, T t, ReadableWorldState readableWorldState) {
        var actionMasks = getActionMasksForPlan(plan);
        var otherActionMasks = getActionMasksForPlan(plan1);

        var noMasksInCommon = Collections.disjoint(actionMasks, otherActionMasks);

        if (noMasksInCommon) {
            // No masks in common for the plans, so fall back on same-goal cost analysis for resolution.
            return ConcurrentPlanExecutor.PlanResolver.<T>preferCheaperSameGoal()
                .resolve(plan, plan1, t, readableWorldState);
        }

        // ⭐⭐ AN INTERRUPTIBLE PLAN YIELDS TO REAL WORK. This is the one exception to "shared mask wins".
        //
        // ⚠⚠ WITHOUT IT, IDLING BLOCKS EVERY JOB. Every movement action declares ActionMasks.MOVE - wandering, egg
        // hauling, vent digging, resin spreading, host capture, combat - so an actor that started a wander could not
        // accept ANY of them until the wander ended on its own. In avp_alien that is a 7-12 second window per stroll,
        // and it reads in game as workers standing around while their hive reports jobs unstaffed.
        //
        // ⭐ The active plan is only displaced when EVERY action in it is interruptible AND the incoming plan has
        // real work in it - so filler yields to work, work never yields to filler, and two pieces of real work still
        // resolve by the old rule. Nothing is interruptible unless it opts in, so existing behaviour is unchanged.
        if (isEntirelyInterruptible(plan1) && !isEntirelyInterruptible(plan)) {
            return Resolution.REPLACE_ACTIVE;
        }

        // Incoming plan shares masks with active plan, so do not accept incoming plan.
        return Resolution.KEEP_ACTIVE;
    }

    /**
     * Whether every action in this plan has opted in to being interrupted.
     * <p>
     * ⚠ ALL, not any: a plan that mixes filler with real work is real work. An empty plan is not interruptible either -
     * there is nothing to displace and answering true would let anything shove aside a plan we cannot see inside.
     * </p>
     */
    private boolean isEntirelyInterruptible(Plan<T> plan) {
        var actions = plan.getActions();

        if (actions.isEmpty()) {
            return false;
        }

        for (var action : actions) {
            if (!(action instanceof BLibAction<?> bLibAction) || !bLibAction.isInterruptible()) {
                return false;
            }
        }

        return true;
    }

    private Set<ActionMask> getActionMasksForPlan(Plan<T> plan) {
        return plan.getActions()
            .stream()
            .flatMap(action -> {
                if (action instanceof BLibAction<?> bLibAction) {
                    return bLibAction.getMasks().stream();
                }

                return Stream.empty();
            })
            .collect(Collectors.toSet());
    }
}
