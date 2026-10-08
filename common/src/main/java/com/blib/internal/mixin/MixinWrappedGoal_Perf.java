package com.blib.internal.mixin;

import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.blib.internal.common.perf.BLibPerfProfiler;

/**
 * Oct 5 - profiler v3: times every vanilla Goal, for any mob of any mod, around the five calls the goal selector makes
 * through its wrapper (canUse, canContinueToUse, start, stop, tick). The time is charged to the mob whose AI step is
 * running and shown per goal class in {@code /blib perf}. Goals never call each other, so one start field is enough.
 * <p>
 * Optional injections; with no session running each is one static read.
 * </p>
 */
@Mixin(WrappedGoal.class)
public abstract class MixinWrappedGoal_Perf {

    @Unique
    private static long blib$goalStart;

    @Unique
    private static final ClassValue<String> BLIB$GOAL_LABEL = new ClassValue<>() {

        @Override
        protected String computeValue(Class<?> type) {
            var name = type.getName();
            var simple = name.substring(name.lastIndexOf('.') + 1);

            // An anonymous goal (Yautja$3) is named after what it extends, so the table still says what it is.
            if (type.isAnonymousClass() && type.getSuperclass() != null) {
                return type.getSuperclass().getSimpleName() + " (" + simple + ")";
            }

            return simple;
        }
    };

    /** A goal that names itself (a wrapper around another goal) is reported by that name. */
    @Unique
    private static String blib$label(net.minecraft.world.entity.ai.goal.Goal goal) {
        return goal instanceof com.blib.api.common.perf.v1.BLibPerf.Named named
            ? named.perfName()
            : BLIB$GOAL_LABEL.get(goal.getClass());
    }

    @Unique
    private void blib$begin() {
        blib$goalStart = BLibPerfProfiler.currentAiStepEntity() == null ? 0L : BLibPerfProfiler.timerStart();
    }

    @Unique
    private void blib$end() {
        if (blib$goalStart != 0L) {
            BLibPerfProfiler.recordLabel(
                BLibPerfProfiler.currentAiStepEntity(),
                BLibPerfProfiler.LABEL_GOAL,
                blib$label(((WrappedGoal) (Object) this).getGoal()),
                blib$goalStart
            );
            blib$goalStart = 0L;
        }
    }

    @Inject(method = "canUse", at = @At("HEAD"), require = 0)
    private void blib$canUseHead(CallbackInfoReturnable<Boolean> cir) {
        if (BLibPerfProfiler.isActive()) {
            blib$begin();
        }
    }

    @Inject(method = "canUse", at = @At("RETURN"), require = 0)
    private void blib$canUseReturn(CallbackInfoReturnable<Boolean> cir) {
        blib$end();
    }

    @Inject(method = "canContinueToUse", at = @At("HEAD"), require = 0)
    private void blib$continueHead(CallbackInfoReturnable<Boolean> cir) {
        if (BLibPerfProfiler.isActive()) {
            blib$begin();
        }
    }

    @Inject(method = "canContinueToUse", at = @At("RETURN"), require = 0)
    private void blib$continueReturn(CallbackInfoReturnable<Boolean> cir) {
        blib$end();
    }

    @Inject(method = "start", at = @At("HEAD"), require = 0)
    private void blib$startHead(CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            blib$begin();
        }
    }

    @Inject(method = "start", at = @At("RETURN"), require = 0)
    private void blib$startReturn(CallbackInfo ci) {
        blib$end();
    }

    @Inject(method = "stop", at = @At("HEAD"), require = 0)
    private void blib$stopHead(CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            blib$begin();
        }
    }

    @Inject(method = "stop", at = @At("RETURN"), require = 0)
    private void blib$stopReturn(CallbackInfo ci) {
        blib$end();
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void blib$tickHead(CallbackInfo ci) {
        if (BLibPerfProfiler.isActive()) {
            blib$begin();
        }
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void blib$tickReturn(CallbackInfo ci) {
        blib$end();
    }
}
