package com.blib.internal.mixin.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.blib.api.common.block.v1.BlockBreakProgressManager;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevel_BlockBreakProgressManager {

    @Inject(at = @At("HEAD"), method = "onBlockStateChange")
    public void blib$onBlockStateChange(BlockPos pos, BlockState blockState, BlockState newState, CallbackInfo callbackInfo) {
        // Sep 28 - this runs for EVERY block change, so it must not send anything unless BLib was tracking break
        // progress at this exact position. See BlockBreakProgressManager.clearProgressIfTracked for the full story.
        var serverLevel = ServerLevel.class.cast(this);
        BlockBreakProgressManager.clearProgressIfTracked(serverLevel, pos);
    }
}
