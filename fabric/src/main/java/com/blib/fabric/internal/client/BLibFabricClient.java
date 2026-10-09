package com.blib.fabric.internal.client;

import com.blib.mod.common.command.BLibRenderProfilerCommands;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.jetbrains.annotations.ApiStatus;

import com.blib.fabric.internal.client.shader.BLibFabricShaders;
import com.blib.internal.client.BLibClient;
import com.blib.internal.client.territory.BLibClaimHud;

@ApiStatus.Internal
public class BLibFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        BLibClient.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(client -> BLibClaimHud.tick());
        BLibFabricShaders.register();
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext) -> dispatcher.register(
                        ClientCommandManager.literal("blibclient")
                                .then(BLibRenderProfilerCommands.build(
                                        FabricClientCommandSource::sendFeedback
                                ))
                )
        );
    }
}
