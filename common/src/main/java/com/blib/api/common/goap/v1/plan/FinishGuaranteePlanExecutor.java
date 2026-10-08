package com.blib.api.common.goap.v1.plan;

import com.just.ai.goap.Agent;
import com.just.ai.goap.action.Action;
import com.just.ai.goap.plan.Plan;
import com.just.ai.goap.plan.executor.PlanExecutor;
import com.just.ai.goap.plan.executor.impl.ConcurrentPlanExecutor;
import com.just.ai.goap.state.ReadableWorldState;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import com.blib.internal.common.goap.BLibGoapErrors;
import com.blib.mod.common.registry.init.BLibGameRules;

/**
 * Wraps a {@link ConcurrentPlanExecutor} so that every action that STARTED also FINISHES.
 * <p>
 * Oct 5 - performance programme, stage 1. Read from the just-goap 0.4.1 bytecode: {@code Plan.update} calls an action's
 * {@code onFinish} when its effects are met (including the last action, which completes the plan) and when its runtime
 * preconditions fail (INVALID). It does NOT call it on three other paths, and all three remove the plan:
 * </p>
 * <ul>
 * <li><b>ABORT</b> - {@code perform} returned {@code Signal.ABORT}; the plan returns ABORTED and the executor drops
 * it.</li>
 * <li><b>Displaced</b> - the plan resolver answered REPLACE_ACTIVE or REJECT_BOTH and {@code supplyPlans} removed the
 * running plan. BLib's {@link ActionMaskPlanResolver} does this to every all-interruptible plan (wandering) the moment
 * real work arrives - so the most common plan in the game was the one losing its cleanup.</li>
 * <li><b>Abandoned</b> - {@code abandonAllPlans}, a plain {@code List.clear()}.</li>
 * </ul>
 * <p>
 * ⭐ HOW A STARTED ACTION IS RECOGNISED. {@code Plan} keeps {@code actionTick} for its current action: 0 until the first
 * {@code perform}, incremented after every {@code perform}, and reset to 0 when the plan moves to the next action
 * (always after that action's {@code onFinish}). So {@code getActionTick() > 0} means exactly "onStart ran and onFinish
 * has not". For a plan that vanished during {@code execute}, ABORT is told apart from INVALID by the tick counter:
 * ABORT increments it (it comes after {@code perform}), INVALID returns before {@code perform} and leaves it unchanged.
 * </p>
 * <p>
 * ⚠ Cleanup runs AFTER the plan has left the active list, so an {@code onFinish} that inspects or abandons plans cannot
 * re-enter itself. Each call is caught on its own: one bad {@code onFinish} is logged once and the rest still run.
 * </p>
 * <p>
 * Allocation-free on the normal path - the snapshot arrays are reused per agent. {@code /gamerule
 * blibGoapFinishGuarantee false} turns all of it into a straight pass-through on the next call.
 * </p>
 */
public final class FinishGuaranteePlanExecutor<T extends LivingEntity> implements PlanExecutor<T> {

    /** ConcurrentPlanExecutor caps BLib agents at 5 concurrent plans; generous headroom for any future raise. */
    private static final int MAX_TRACKED_PLANS = 16;

    private final T actor;

    private final ConcurrentPlanExecutor<T> delegate;

    @SuppressWarnings("unchecked")
    private final Plan<T>[] snapshotPlans = (Plan<T>[]) new Plan<?>[MAX_TRACKED_PLANS];

    private final int[] snapshotActionIndex = new int[MAX_TRACKED_PLANS];

    private final int[] snapshotActionTick = new int[MAX_TRACKED_PLANS];

    private int snapshotCount;

    private @Nullable Agent<T> agent;

    public FinishGuaranteePlanExecutor(T actor, ConcurrentPlanExecutor<T> delegate) {
        this.actor = actor;
        this.delegate = delegate;
    }

    /**
     * The wrapped executor, for code that needs the concrete type (the GOAP debug view reads its active plans).
     *
     * @return the ConcurrentPlanExecutor behind {@code executor}, or null if it is neither one nor a wrapper of one
     */
    @SuppressWarnings("unchecked")
    public static <T> @Nullable ConcurrentPlanExecutor<T> unwrapConcurrent(PlanExecutor<T> executor) {
        if (executor instanceof FinishGuaranteePlanExecutor<?> wrapper) {
            return (ConcurrentPlanExecutor<T>) wrapper.delegate;
        }

        return executor instanceof ConcurrentPlanExecutor<T> concurrent ? concurrent : null;
    }

    public ConcurrentPlanExecutor<T> getDelegate() {
        return delegate;
    }

    @Override
    public Result execute(ExecutionContext<T> context) {
        agent = context.agent();

        if (!isEnabled()) {
            return delegate.execute(context);
        }

        takeSnapshot();

        var result = delegate.execute(context);

        for (var i = 0; i < snapshotCount; i++) {
            var plan = snapshotPlans[i];

            // ⚠ Gone, still on the same action, and its tick moved by exactly one: perform ran and returned ABORT.
            // FINISHED moves the index; INVALID leaves the tick where it was - both already called onFinish.
            if (
                !isActive(plan)
                    && plan.getCurrentActionIndex() == snapshotActionIndex[i]
                    && plan.getActionTick() == snapshotActionTick[i] + 1
            ) {
                finishCurrentAction(plan, context.currentWorldState(), context.previousWorldState(), "onFinish (aborted)");
            }
        }

        clearSnapshot();

        return result;
    }

    @Override
    public void supplyPlans(SupplyContext<T> context) {
        agent = context.agent();

        if (!isEnabled()) {
            delegate.supplyPlans(context);
            return;
        }

        takeSnapshot();
        delegate.supplyPlans(context);
        finishVanishedStartedPlans(context.worldState(), "onFinish (displaced)");
        clearSnapshot();
    }

    @Override
    public void supplyPlans(List<Plan<T>> plans, T actor, ReadableWorldState worldState) {
        if (!isEnabled()) {
            delegate.supplyPlans(plans, actor, worldState);
            return;
        }

        takeSnapshot();
        delegate.supplyPlans(plans, actor, worldState);
        finishVanishedStartedPlans(worldState, "onFinish (displaced)");
        clearSnapshot();
    }

    @Override
    public boolean hasActivePlans() {
        return delegate.hasActivePlans();
    }

    @Override
    public void abandonAllPlans() {
        if (!isEnabled() || !delegate.hasActivePlans()) {
            delegate.abandonAllPlans();
            return;
        }

        takeSnapshot();
        delegate.abandonAllPlans();

        var worldState = agent == null ? null : agent.getCurrentWorldState();
        finishVanishedStartedPlans(worldState, "onFinish (abandoned)");
        clearSnapshot();
    }

    private boolean isEnabled() {
        return actor.level().getGameRules().getBoolean(BLibGameRules.GOAP_FINISH_GUARANTEE);
    }

    private void takeSnapshot() {
        var activePlans = delegate.getActivePlans();
        var count = Math.min(activePlans.size(), MAX_TRACKED_PLANS);

        for (var i = 0; i < count; i++) {
            var plan = activePlans.get(i);
            snapshotPlans[i] = plan;
            snapshotActionIndex[i] = plan.getCurrentActionIndex();
            snapshotActionTick[i] = plan.getActionTick();
        }

        snapshotCount = count;
    }

    private void clearSnapshot() {
        for (var i = 0; i < snapshotCount; i++) {
            snapshotPlans[i] = null;
        }

        snapshotCount = 0;
    }

    private boolean isActive(Plan<T> plan) {
        var activePlans = delegate.getActivePlans();

        for (var i = 0; i < activePlans.size(); i++) {
            if (activePlans.get(i) == plan) {
                return true;
            }
        }

        return false;
    }

    private void finishVanishedStartedPlans(@Nullable ReadableWorldState worldState, String where) {
        for (var i = 0; i < snapshotCount; i++) {
            var plan = snapshotPlans[i];

            if (plan.getActionTick() > 0 && !isActive(plan)) {
                finishCurrentAction(plan, worldState, worldState, where);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void finishCurrentAction(
        Plan<T> plan,
        @Nullable ReadableWorldState worldState,
        @Nullable ReadableWorldState previousWorldState,
        String where
    ) {
        var actions = plan.getActions();
        var index = plan.getCurrentActionIndex();

        if (index < 0 || index >= actions.size()) {
            return;
        }

        var action = (Action<T>) (Action<?>) actions.get(index);

        try {
            var context = new Action.Context<T>();
            context.set(action, actor, agent, plan.getActionBlackboard(), plan, worldState, previousWorldState);
            action.onFinish(context);
        } catch (RuntimeException | LinkageError | StackOverflowError error) {
            BLibGoapErrors.report(where + " of " + action.getName(), actor, error);
        }
    }
}
