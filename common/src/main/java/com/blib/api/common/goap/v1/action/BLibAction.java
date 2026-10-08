package com.blib.api.common.goap.v1.action;

import com.just.ai.goap.action.Action;
import com.just.ai.goap.action.DelegatingAction;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class BLibAction<T> extends DelegatingAction<T> {

    public static <T> ConcreteBuilder<T> builder(String name) {
        return new ConcreteBuilder<>(name);
    }

    private final Set<ActionMask> masks;

    private final boolean interruptible;

    protected BLibAction(Action<T> delegate, Set<ActionMask> masks) {
        this(delegate, masks, false);
    }

    protected BLibAction(Action<T> delegate, Set<ActionMask> masks, boolean interruptible) {
        super(delegate);
        this.masks = masks;
        this.interruptible = interruptible;
    }

    /** Profiler v3: this action's label in /blib perf, built once. */
    private String perfLabel;

    private String perfLabel() {
        var label = perfLabel;

        if (label == null) {
            label = getName();
            perfLabel = label;
        }

        return label;
    }

    /*
     * Oct 5 - profiler v3: each BLib action's perform/onStart/onFinish is timed and charged to its actor under the
     * action's name, so /blib perf can say which action the AI column is spent in. Nothing is recorded, and only one
     * static read is spent, while no session runs.
     */

    @Override
    public com.just.ai.goap.action.Action.Signal perform(com.just.ai.goap.action.Action.Context<? extends T> context) {
        var start = com.blib.internal.common.perf.BLibPerfProfiler.timerStart();

        if (start == 0L) {
            return super.perform(context);
        }

        com.blib.internal.common.perf.BLibPerfProfiler.enterAction();

        try {
            return super.perform(context);
        } finally {
            com.blib.internal.common.perf.BLibPerfProfiler.exitAction();
            recordPerf(context, start);
        }
    }

    @Override
    public void onStart(com.just.ai.goap.action.Action.Context<? extends T> context) {
        var start = com.blib.internal.common.perf.BLibPerfProfiler.timerStart();

        if (start != 0L) {
            com.blib.internal.common.perf.BLibPerfProfiler.enterAction();
        }

        try {
            super.onStart(context);
        } finally {
            if (start != 0L) {
                com.blib.internal.common.perf.BLibPerfProfiler.exitAction();
            }

            recordPerf(context, start);
        }
    }

    @Override
    public void onFinish(com.just.ai.goap.action.Action.Context<? extends T> context) {
        var start = com.blib.internal.common.perf.BLibPerfProfiler.timerStart();

        if (start != 0L) {
            com.blib.internal.common.perf.BLibPerfProfiler.enterAction();
        }

        try {
            super.onFinish(context);
        } finally {
            if (start != 0L) {
                com.blib.internal.common.perf.BLibPerfProfiler.exitAction();
            }

            recordPerf(context, start);
        }
    }

    private void recordPerf(com.just.ai.goap.action.Action.Context<? extends T> context, long start) {
        if (start != 0L && context.getActor() instanceof net.minecraft.world.entity.Entity entity) {
            com.blib.internal.common.perf.BLibPerfProfiler.recordLabel(
                entity,
                com.blib.internal.common.perf.BLibPerfProfiler.LABEL_ACTION,
                perfLabel(),
                start
            );
        }
    }

    public Set<ActionMask> getMasks() {
        return masks;
    }

    /**
     * Whether a plan built on this action may be thrown away so a competing plan can run.
     * <p>
     * <b>Why this exists.</b> {@code ActionMaskPlanResolver} refuses any incoming plan that shares a mask with the
     * running one, with no notion of priority - and in practice EVERY movement action shares the MOVE mask. An actor
     * that is idly wandering therefore cannot start hauling, building or fighting until its wander finishes on its own,
     * which reads in game as workers standing around ignoring their jobs.
     * </p>
     * <p>
     * Marking the idle behaviour interruptible lets real work displace it. Default is {@code false}, so nothing already
     * written changes behaviour: an action must opt IN to being interrupted.
     * </p>
     */
    public boolean isInterruptible() {
        return interruptible;
    }

    public abstract static class Builder<T, B extends Builder<T, B>> extends DelegatingAction.Builder<T, B> {

        protected final Set<ActionMask> masks;

        protected boolean interruptible;

        protected Builder(String name) {
            super(name);
            this.masks = new HashSet<>();
        }

        /**
         * Marks this action as one that may be abandoned mid-plan so a competing plan can take its masks.
         * <p>
         * Reserve it for filler behaviour - idling, wandering, looking around. An action that is part-way through
         * something with state attached (carrying an egg, mid-transition) must NOT be interruptible, or the plan can be
         * dropped between the pick-up and the put-down.
         * </p>
         */
        public B markInterruptible() {
            this.interruptible = true;
            return self();
        }

        public B addMask(ActionMask mask) {
            masks.add(mask);
            return self();
        }

        public final B addMasks(ActionMask actionMask, ActionMask... masks) {
            addMask(actionMask);
            Collections.addAll(this.masks, masks);
            return self();
        }

        @Override
        protected BLibAction<T> build(Action<T> delegate) {
            return new BLibAction<>(delegate, masks, interruptible);
        }

        @Override
        public BLibAction<T> build() {
            return (BLibAction<T>) super.build();
        }
    }

    public static class ConcreteBuilder<T> extends Builder<T, ConcreteBuilder<T>> {

        protected ConcreteBuilder(String name) {
            super(name);
        }

        @Override
        protected ConcreteBuilder<T> self() {
            return this;
        }
    }
}
