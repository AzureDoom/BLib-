package com.blib.neoforge.internal.client;

import com.blib.mod.common.command.BLibRenderProfilerCommands;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.ApiStatus;

import com.blib.internal.client.BLibClient;
import com.blib.internal.client.territory.BLibClaimHud;
import com.blib.mod.BLib;
import com.blib.neoforge.internal.client.shader.BLibNeoForgeShaders;

@ApiStatus.Internal
@Mod(value = BLib.MOD_ID, dist = Dist.CLIENT)
public class BLibNeoForgeClient {

    public BLibNeoForgeClient(IEventBus modEventBus) {
        BLibClient.initialize();
        NeoForge.EVENT_BUS.<ClientTickEvent.Post>addListener(event -> BLibClaimHud.tick());
        BLibNeoForgeShaders.register(modEventBus);
        NeoForge.EVENT_BUS.<RegisterClientCommandsEvent>addListener(
                event -> event.getDispatcher().register(
                        Commands.literal("blibclient")
                                .then(BLibRenderProfilerCommands.build(
                                        (source, message) -> source.sendSuccess(() -> message, false)
                                ))
                )
        );
    }
}
