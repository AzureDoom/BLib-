package com.blib.api.common.goap.v1;

import com.just.ai.goap.Agent;
import com.just.ai.goap.graph.Graph;
import com.just.ai.goap.plan.ReplanPolicies;
import com.just.ai.goap.plan.executor.impl.ConcurrentPlanExecutor;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.UnaryOperator;

import com.blib.api.common.goap.v1.plan.ActionMaskPlanResolver;
import com.blib.api.common.goap.v1.plan.FinishGuaranteePlanExecutor;
import com.blib.internal.common.goap.BLibGoapErrors;
import com.blib.mod.common.registry.init.BLibGameRules;

public class LivingEntityAgent<T extends LivingEntity> {

    /**
     * How long an agent rests after its update threw, in ticks.
     * <p>
     * ⚠ A failure is almost always deterministic - the same sensor throws on the same input every tick - so retrying at
     * once just pays for another exception and stack trace each tick. One second lets the world move on (a target dies,
     * a block changes) before trying again, while still recovering on its own if the cause was transient.
     * </p>
     */
    private static final int ERROR_REST_TICKS = 20;

    private final Agent<T> agent;

    private boolean isEnabled;

    /** The actor's tickCount before which update does nothing, after an isolated failure. */
    private int restUntilTick = Integer.MIN_VALUE;

    /** Set once a dying actor's plans have been finished, so it is done exactly once. */
    private boolean finishedOnDeath;

    public LivingEntityAgent(
        T actor,
        UnaryOperator<Agent.Builder<T>> builderUnaryOperator
    ) {
        // ⭐ Oct 5 - the executor is wrapped so every action that started also finishes. See FinishGuaranteePlanExecutor
        // for the three paths just-goap skips onFinish on. It passes straight through while the game rule
        // blibGoapFinishGuarantee is false, so the wrapper itself is the only permanent change.
        this.agent = Agent.builder(actor)
            .withPlanExecutor(
                new FinishGuaranteePlanExecutor<>(
                    actor,
                    ConcurrentPlanExecutor.<T>builder()
                        .withPlanResolver(new ActionMaskPlanResolver<>())
                        .withMaxConcurrentPlans(5)
                        .build()
                )
            )
            .withReplanPolicy(ReplanPolicies.ifNoActivePlans())
            .apply(builderUnaryOperator)
            .build();
        this.isEnabled = true;
    }

    public void update(Graph<T> graph) {
        var actor = agent.getActor();

        if (!isEnabled) {
            return;
        }

        if (actor.isDeadOrDying()) {
            finishPlansOnDeath(actor);
            return;
        }

        if (actor.tickCount < restUntilTick) {
            return;
        }

        var gameRules = actor.level().getGameRules();

        // ⭐ Sep 27 — just-goap 0.4.1. Read every tick, so /gamerule blibGoapSensingOptimizations false takes effect on
        // the next tick with no restart. A single rule lookup; the agent update after it costs orders of magnitude
        // more.
        // ⚠ FALLBACK TO just-goap 0.4.0: this statement is the ONLY BLib code that uses 0.4.1 API. Delete it (and the
        // BLibGameRules import) when pointing just_goap_version back at 0.4.0, or common will not compile.
        agent.setSensingOptimizationsEnabled(gameRules.getBoolean(BLibGameRules.GOAP_SENSING_OPTIMIZATIONS));

        // ⭐⭐ Oct 5 - ERROR ISOLATION. A throw from a sensor, the planner or an action used to escape into the entity
        // tick, where vanilla turns it into a "Ticking entity" crash that takes the whole server down for one bad mob.
        // Now it is logged once per cause, the plans are abandoned (so the finish guarantee cleans up after them) and
        // this agent rests. ⚠ With blibGoapErrorIsolation false it is rethrown untouched - the old crash, on purpose,
        // for when a crash report is the thing wanted.
        // ⚠ OutOfMemoryError and other VirtualMachineErrors are deliberately NOT caught. StackOverflowError is: a
        // runaway recursion in one mob's sensor is exactly the failure this exists for.
        // Oct 6 - profiler v3: time this graph's sensors while a /blib perf session runs (no-op otherwise).
        com.blib.internal.common.perf.TimedSensors.ensureWrapped(graph);

        try {
            agent.update(graph);
        } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError error) {
            if (!gameRules.getBoolean(BLibGameRules.GOAP_ERROR_ISOLATION)) {
                throw error;
            }

            BLibGoapErrors.report("agent update", actor, error);
            restUntilTick = actor.tickCount + ERROR_REST_TICKS;

            try {
                agent.abandonPlan();
            } catch (RuntimeException | LinkageError | StackOverflowError abandonError) {
                BLibGoapErrors.report("abandon after failure", actor, abandonError);
            }
        }
    }

    /**
     * A dying actor stops updating, and its running plans used to stop with it - never finished. Any cleanup in those
     * actions' onFinish (a reservation, a claimed egg, a carried flag) was simply lost. With the finish guarantee on,
     * the plans are abandoned once, which runs onFinish for every action that had started.
     */
    private void finishPlansOnDeath(T actor) {
        if (finishedOnDeath || actor.level().isClientSide()) {
            return;
        }

        finishedOnDeath = true;

        if (!agent.hasPlan() || !actor.level().getGameRules().getBoolean(BLibGameRules.GOAP_FINISH_GUARANTEE)) {
            return;
        }

        try {
            agent.abandonPlan();
        } catch (RuntimeException | LinkageError | StackOverflowError error) {
            BLibGoapErrors.report("abandon on death", actor, error);
        }
    }

    /**
     * Whether this agent currently has a plan it is executing.
     * <p>
     * ⭐ THE ONLY SAFE THING TO THROTTLE IS AN AGENT WITH NOTHING TO DO. Agent.update runs sensing, planning AND
     * execution together and the phases are private, so skipping an update skips execution too - and execution is what
     * drives BLib's navigator, block breaking and door handling. Throttling a working agent therefore slows walking and
     * digging, which is exactly what two earlier attempts did.
     * </p>
     * <p>
     * ⚠ With no plan, update does no execution at all - it only senses and looks for one. Running that at 5 Hz instead
     * of 20 costs nothing anyone can see, and it is where an idle hive spends its time.
     * </p>
     */
    public boolean hasPlan() {
        return agent.hasPlan();
    }

    public void setEnabled(boolean isEnabled) {
        this.isEnabled = isEnabled;
    }

    public Agent<T> getBackingAgent() {
        return agent;
    }
}
