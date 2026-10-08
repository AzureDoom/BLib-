package com.blib.internal.mixin;

import net.minecraft.network.protocol.common.custom.GoalDebugPayload;
import net.minecraft.network.protocol.common.custom.PathfindingDebugPayload;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

import com.blib.mod.BLib;
import com.blib.mod.common.property.BLibModProperties;
import com.blib.mod.common.property.BLibModPropertyAccess;

@Mixin(DebugPackets.class)
public abstract class MixinDebugPackets {

    @Inject(at = @At("HEAD"), method = "sendPathFindingPacket")
    private static void sendPathFindingPacket(Level level, Mob mob, Path path, float maxDistanceToWaypoint, CallbackInfo callbackInfo) {
        var access = BLibModPropertyAccess.INSTANCE;

        if (!access.get(BLibModProperties.Debug.Render.ENABLED)) {
            return;
        }

        if (path == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (serverLevel.getGameRules().getBoolean(GameRules.RULE_REDUCEDDEBUGINFO)) {
            return;
        }

        var payload = new PathfindingDebugPayload(mob.getId(), path, maxDistanceToWaypoint);

        BLib.MOD.networking().sendToAllClientsTrackingEntity(mob, payload);
    }

    @Inject(at = @At("HEAD"), method = "sendGoalSelector")
    private static void sendGoalSelector(Level level, Mob mob, GoalSelector goalSelector, CallbackInfo callbackInfo) {
        // ⚠⚠ Sep 28 - Spark profile. Vanilla calls this for EVERY mob on EVERY AI tick (Mob.serverAiStep ->
        // sendDebugPackets), and vanilla's own body is empty. Without this gate BLib built a goal list and sent a
        // packet
        // to every tracking player, per mob, 20 times a second - fish, squid, cows, everything - purely to feed a debug
        // overlay that is off by default. It was the largest single BLib cost in the profile (0.48% of the server
        // thread
        // with ~110 entities loaded, before counting the client decoding every packet). Now gated exactly like
        // sendPathFindingPacket above: nothing is built or sent unless BLib's debug rendering is switched on.
        if (!BLibModPropertyAccess.INSTANCE.get(BLibModProperties.Debug.Render.ENABLED)) {
            return;
        }

        if (!(level instanceof ServerLevel serverLevel) || mob == null || goalSelector == null) {
            return;
        }

        if (serverLevel.getGameRules().getBoolean(GameRules.RULE_REDUCEDDEBUGINFO)) {
            return;
        }

        var debugGoals = new ArrayList<GoalDebugPayload.DebugGoal>();

        for (var availableGoal : goalSelector.getAvailableGoals()) {
            var debugGoal = new GoalDebugPayload.DebugGoal(
                availableGoal.getPriority(),
                availableGoal.isRunning(),
                availableGoal.getGoal().getClass().getSimpleName()
            );

            debugGoals.add(debugGoal);
        }

        var payload = new GoalDebugPayload(mob.getId(), mob.blockPosition(), debugGoals);

        BLib.MOD.networking().sendToAllClientsTrackingEntity(mob, payload);
    }

}
