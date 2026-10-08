package com.blib.internal.mixin;

import com.just.ai.goap.graph.Graph;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.blib.api.common.goap.v1.GOAPUser;
import com.blib.api.common.goap.v1.LivingEntityAgent;
import com.blib.internal.common.perf.BLibPerfProfiler;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity_GOAPUser extends Entity implements GOAPUser<LivingEntity> {

    @Unique
    private LivingEntityAgent<LivingEntity> blib$goapAgent;

    public MixinLivingEntity_GOAPUser(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        var self = LivingEntity.class.cast(this);
        this.blib$goapAgent = new LivingEntityAgent<>(self, this::blib$applyGOAPAgentProperties);
    }

    /**
     * How often an IDLE agent re-senses and re-plans, in ticks.
     * <p>
     * 🚨🚨 THIS RAN EVERY TICK FOR EVERY AGENT AND IT IS THE SINGLE LARGEST COST IN THE MOD. Measured on a live server
     * with `/avp hive diag all`: 43 xenomorphs took 34% of the entire server tick - a drone cost 0.796 ms per tick and
     * a warrior 0.228 ms, against 0.034 ms for a vanilla chicken. Agent.update runs all three phases every time:
     * prepareWorldStates (31 sensors for a xenomorph), supplyPlansIfNeeded, and executePlans.
     * </p>
     * <p>
     * ⚠⚠ THE PHASES CANNOT BE SPLIT - they are private inside just-goap - so the whole update is throttled together.
     * That is safe because nothing time-critical lives in it: navigation runs in vanilla Mob.tick every tick, so a mob
     * keeps moving smoothly along a path it already has, and an attack in progress is driven by its own executor in the
     * entity's tick, so its timing is untouched.
     * </p>
     */
    @Unique
    private static final int blib$IDLE_GOAP_INTERVAL_TICKS = 4;

    /**
     * ⭐ COMBAT IS NEVER THROTTLED. An agent with a target re-plans every tick exactly as before, so reaction time in a
     * fight is unchanged. The saving comes from the majority of a hive that is NOT fighting - workers, haulers, idlers
     * - which is precisely the population that grows without bound.
     */
    @Unique
    private boolean blib$isGoapCombatActive() {
        return LivingEntity.class.cast(this) instanceof Mob mob && mob.getTarget() != null;
    }

    @Inject(at = @At("HEAD"), method = "tick")
    public void tick(CallbackInfo callbackInfo) {
        if (level().isClientSide || blib$goapAgent == null) {
            return;
        }

        // ⚠⚠ RESPECT VANILLA'S noAi. Mob.serverAiStep — goals, brain, navigation — is skipped entirely while isNoAi()
        // is set, and this agent ran from LivingEntity.tick with no such check. So the one switch vanilla offers to
        // freeze a mob (/data merge NoAI:1, and avp_predator's capture net, which is nothing but setNoAi(true))
        // never reached a GOAP mob: netted xenomorphs kept planning and walking. Same contract as vanilla now.
        if ((Object) this instanceof Mob mob && mob.isNoAi()) {
            return;
        }

        var graph = blib$getGOAPGraphOrNull();

        // ⚠⚠ THE IDLE THROTTLE WAS REMOVED. Running the agent every 4 ticks and ticking the navigator separately
        // was meant to keep movement smooth, and it did not - drones crawled. Two attempts at splitting the two
        // apart both regressed movement, so the agent runs every tick again until that split can be done properly.
        //
        // ⚠ THE SAVING IS REAL AND STILL WANTED - GOAP was measured at 13% of the server thread - but not at the
        // cost of every worker walking at a quarter speed. The right fix is for BLib's navigator to be driven from
        // the entity tick rather than from inside the movement ACTION, which is a larger change than a mixin guard.
        //
        // ⭐ Per-entity gating still works and is used: an Ovomorph returns a null graph unless it wants to hatch,
        // which costs nothing and cannot affect movement because eggs do not move.
        if (graph == null) {
            return;
        }

        // ⭐⭐ THROTTLE ONLY AN AGENT WITH NO PLAN. Agent.update runs sensing, planning and EXECUTION together, and
        // execution is what drives the navigator, block breaking and door handling - so skipping an update on a
        // working agent slows walking and digging. Two earlier attempts did exactly that.
        //
        // ⚠ An agent with no plan executes nothing: the update only senses and looks for one. Running that at 5 Hz
        // instead of 20 is invisible, and it is where an idle hive spends its time. The moment a plan exists this
        // returns to full rate, so nothing that is actually doing something is ever throttled.
        //
        // ⚠ Staggered by entity id so agents do not all re-plan on the same tick.
        if (
            !blib$goapAgent.hasPlan()
                && !blib$isGoapCombatActive()
                && (tickCount + getId()) % blib$IDLE_GOAP_INTERVAL_TICKS != 0
        ) {
            return;
        }

        // Oct 5 - /blib perf. One static boolean read when no session runs.
        if (BLibPerfProfiler.isActive()) {
            var startNanos = System.nanoTime();
            blib$goapAgent.update(graph);
            BLibPerfProfiler.recordAi(this, System.nanoTime() - startNanos);
            return;
        }

        blib$goapAgent.update(graph);
    }

    @Override
    public @Nullable LivingEntityAgent<LivingEntity> blib$getGOAPAgentOrNull() {
        return blib$goapAgent;
    }

    @Override
    public Graph<LivingEntity> blib$getGOAPGraphOrNull() {
        return null;
    }
}
