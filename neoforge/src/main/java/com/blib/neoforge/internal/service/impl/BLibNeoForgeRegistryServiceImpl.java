package com.blib.neoforge.internal.service.impl;

import com.just.core.functional.tuple.Tuple2;
import com.just.core.functional.tuple.Tuple4;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.level.ItemLike;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.function.Supplier;

import com.blib.api.BLibAPI;
import com.blib.api.common.codec.v1.BLibCodecs;
import com.blib.api.common.entity.v1.spawning.BLibEntitySpawnData;
import com.blib.api.common.event.v1.BLibCommonSetupEvent;
import com.blib.api.common.mod.v1.BLibMod;
import com.blib.api.common.mod.v1.model.DistributionEnvironmentType;
import com.blib.api.common.network.v1.NetworkHandler;
import com.blib.api.common.network.v1.PacketDirection;
import com.blib.api.common.registry.v1.BLibHolder;
import com.blib.internal.service.BLibRegistryService;
import com.blib.neoforge.internal.data.BLibNeoForgeCompostableDataMapProvider;
import com.blib.neoforge.internal.data.BLibNeoForgeEntitySpawnDataProvider;
import com.blib.neoforge.internal.data.BLibNeoForgeFurnaceFuelDataMapProvider;

@ApiStatus.Internal
public class BLibNeoForgeRegistryServiceImpl implements BLibRegistryService {

    @Override
    public <T> Holder<T> register(BLibHolder<T> holder, Supplier<? extends T> valueFactory) {
        var blibRegistry = holder.getRegistry();
        var modContainer = getModContainer(blibRegistry.getMod());
        var backingRegistry = blibRegistry.getBackingRegistry();
        @SuppressWarnings("unchecked")
        var deferredRegister = (DeferredRegister<T>) modContainer.getDeferredRegister(backingRegistry);

        if (deferredRegister == null) {
            throw new IllegalArgumentException("Unhandled registry: " + backingRegistry);
        }

        return deferredRegister.register(holder.getPath(), valueFactory);
    }

    @Override
    public void registerCommand(BLibMod mod, LiteralArgumentBuilder<CommandSourceStack> literalArgumentBuilder) {
        getModContainer(mod)
            .registerCommand(literalArgumentBuilder);
    }

    @Override
    public void registerCompostable(BLibHolder<? extends ItemLike> holder, float chance, boolean villagersCanCompost, boolean replace) {
        getModContainer(holder)
            .registerCompostable(new Tuple4<>(holder, chance, villagersCanCompost, replace));
    }

    @Override
    public void registerEntityAttributes(
        BLibHolder<? extends EntityType<? extends LivingEntity>> holder,
        Supplier<AttributeSupplier.Builder> attributeSupplierBuilderSupplier
    ) {
        getModContainer(holder)
            .registerEntityAttributes(holder, attributeSupplierBuilderSupplier);
    }

    @Override
    public <T extends Mob> void registerEntitySpawnData(BLibEntitySpawnData<T> spawnData) {
        getModContainer(spawnData.getEntityTypeHolder())
            .registerEntitySpawnData(spawnData);
    }

    @Override
    public void registerBrewingRecipe(BLibMod mod, Holder<Potion> input, Supplier<? extends Item> ingredient, Holder<Potion> output) {
        getModContainer(mod)
            .registerBrewingRecipe(input, ingredient, output);
    }

    @Override
    public void registerFurnaceFuel(BLibHolder<? extends ItemLike> holder, int burnTimeInTicks) {
        getModContainer(holder)
            .registerFurnaceFuel(new Tuple2<>(holder, burnTimeInTicks));
    }

    @Override
    public <T extends CustomPacketPayload> void registerPacketHandler(BLibMod mod, NetworkHandler<T> networkHandler) {
        getModContainer(mod)
            .registerPacketHandlers(networkHandler);
    }

    @Override
    public <T extends CustomPacketPayload> void registerPacketDirection(BLibMod mod, PacketDirection<T> packetDirection) {
        // ⚠⚠ Aug 27 — this was a silent NO-OP, and it broke DEDICATED SERVERS the first time a mod shipped an
        // S2C payload whose handler lives in client init (avp_predator 0.1.5: cloak_state, mud_state). The type
        // then existed only where a client had run its init — so singleplayer worked, and every join to a
        // dedicated NeoForge server was refused at negotiation with "channel missing on the server side, but
        // required on the client". Directions are registered from COMMON init on purpose: collect them here and
        // flush them in the RegisterPayloadHandlersEvent below, exactly as the Fabric service already does via
        // PayloadTypeRegistry.
        getModContainer(mod)
            .registerPacketDirection(packetDirection);
    }

    @Override
    public void registerReloadListener(BLibMod mod, String path, PreparableReloadListener listener, PackType packType) {
        switch (packType) {
            case CLIENT_RESOURCES -> registerClientReloadListener(listener);
            case SERVER_DATA -> getModContainer(mod)
                .registerReloadListener(listener, packType);
        }
    }

    @Override
    public void registerVillagerTrade(
        BLibHolder<VillagerProfession> holder,
        int level,
        List<VillagerTrades.ItemListing> villagerTradeItemListings
    ) {
        getModContainer(holder)
            .registerVillagerTrade(holder, level, villagerTradeItemListings);
    }

    public BLibNeoForgeModContainer getModContainer(BLibHolder<?> holder) {
        return getModContainer(holder.getRegistry().getMod());
    }

    public BLibNeoForgeModContainer getModContainer(BLibMod mod) {
        return BLibNeoForgeModContainerLookup.INSTANCE.get(mod);
    }

    /* package-private */ void initialize(BLibMod mod, IEventBus eventBus) {
        var modContainer = getModContainer(mod);

        modContainer
            .getDeferredRegisters()
            .forEach(deferredRegister -> deferredRegister.register(eventBus));

        eventBus.<EntityAttributeCreationEvent>addListener(event -> onRegisterEntityAttributes(mod, event));
        eventBus.<RegisterSpawnPlacementsEvent>addListener(event -> onRegisterEntitySpawnPlacements(mod, event));

        eventBus.<FMLCommonSetupEvent>addListener(
            event -> modContainer.onCommonSetup()
                .getListeners()
                .forEach(BLibCommonSetupEvent::invoke)
        );

        eventBus.<NewRegistryEvent>addListener(event -> modContainer.getCustomRegistryEntries().forEach(event::register));

        eventBus.<GatherDataEvent>addListener(event -> {
            var generator = event.getGenerator();
            var packOutput = generator.getPackOutput();
            var lookupProvider = event.getLookupProvider();
            var run = event.includeServer();

            generator.addProvider(run, new BLibNeoForgeCompostableDataMapProvider(mod, packOutput, lookupProvider));
            generator.addProvider(run, new BLibNeoForgeEntitySpawnDataProvider(mod, lookupProvider));
            generator.addProvider(run, new BLibNeoForgeFurnaceFuelDataMapProvider(mod, packOutput, lookupProvider));
        });

        eventBus.<RegisterPayloadHandlersEvent>addListener(event -> {
            var registrar = event.registrar("1")
                .executesOn(HandlerThread.NETWORK);

            modContainer.getNetworkHandlers()
                .forEach(networkHandler -> {
                    @SuppressWarnings("unchecked")
                    var typedNetworkHandler = (NetworkHandler<CustomPacketPayload>) networkHandler;

                    switch (typedNetworkHandler) {
                        case NetworkHandler.FromClient<CustomPacketPayload> handler -> registrar.playToServer(
                            handler.type(),
                            BLibCodecs.Stream.toMojang(handler.codec()),
                            (payload, context) -> context.enqueueWork(() -> handler.payloadConsumer().accept(payload, context.player()))
                        );
                        case NetworkHandler.FromEither<CustomPacketPayload> handler -> registrar.playBidirectional(
                            handler.type(),
                            BLibCodecs.Stream.toMojang(handler.codec()),
                            new DirectionalPayloadHandler<>(
                                (payload, context) -> context.enqueueWork(
                                    () -> handler.fromServerPayloadConsumer().accept(payload, context.player())
                                ),
                                (payload, context) -> context.enqueueWork(
                                    () -> handler.fromClientPayloadConsumer().accept(payload, context.player())
                                )
                            )
                        );
                        case NetworkHandler.FromServer<CustomPacketPayload> handler -> registrar.playToClient(
                            handler.type(),
                            BLibCodecs.Stream.toMojang(handler.codec()),
                            (payload, context) -> context.enqueueWork(() -> handler.payloadConsumer().accept(payload, context.player()))
                        );
                    }
                });

            // ⚠⚠ Aug 27 — flush the COMMON-registered packet directions too. NeoForge negotiation requires every
            // required channel to exist on BOTH sides, and a handler-only flush registers a type only on the dist
            // whose init created the handler — a client-init S2C handler therefore left the type unknown to
            // dedicated servers, which refused every 0.1.5 join. Types already covered by a real handler above are
            // skipped (double registration throws); the rest get the type with a no-op handler — correct on the
            // dist that only SENDS the payload, since sending needs the type, not the handler.
            var handlerCoveredTypes = modContainer.getNetworkHandlers()
                .stream()
                .map(NetworkHandler::type)
                .collect(java.util.stream.Collectors.toSet());

            modContainer.getPacketDirections()
                .forEach(packetDirection -> {
                    if (handlerCoveredTypes.contains(packetDirection.type())) {
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    var typedDirection = (PacketDirection<CustomPacketPayload>) packetDirection;
                    var codec = BLibCodecs.Stream.toMojang(typedDirection.codec());

                    switch (typedDirection) {
                        case PacketDirection.C2S<CustomPacketPayload> ignored -> registrar.playToServer(
                            typedDirection.type(),
                            codec,
                            (payload, context) -> {}
                        );
                        case PacketDirection.S2C<CustomPacketPayload> ignored -> registrar.playToClient(
                            typedDirection.type(),
                            codec,
                            (payload, context) -> {}
                        );
                        case PacketDirection.BI<CustomPacketPayload> ignored -> registrar.playBidirectional(
                            typedDirection.type(),
                            codec,
                            (payload, context) -> {}
                        );
                    }
                });
        });

        NeoForge.EVENT_BUS.<RegisterBrewingRecipesEvent>addListener(event -> {
            var builder = event.getBuilder();

            modContainer.getBrewingRecipeData()
                .forEach(recipe -> builder.addMix(recipe.v1(), recipe.v2().get(), recipe.v3()));
        });

        NeoForge.EVENT_BUS.<RegisterCommandsEvent>addListener(
            event -> modContainer.getLiteralArgumentBuilders()
                .forEach(literalArgumentBuilder -> event.getDispatcher().register(literalArgumentBuilder))
        );

        NeoForge.EVENT_BUS.<AddReloadListenerEvent>addListener(
            event -> modContainer.getReloadListeners()
                .forEach(tuple2 -> event.addListener(tuple2.v1()))
        );

        NeoForge.EVENT_BUS.<VillagerTradesEvent>addListener(event -> {
            var trades = event.getTrades();

            modContainer
                .getVillagerTradeData()
                .forEach(villagerTradeData -> {
                    if (event.getType() == villagerTradeData.v1().get()) {
                        trades.get(villagerTradeData.v2()).addAll(villagerTradeData.v3());
                    }
                });
        });

        modContainer.preLevelTick().initialize();
        modContainer.preBlockBreak().initialize();

        modContainer.postLevelTick().initialize();
        modContainer.postScreenInit().initialize();

        modContainer.onPlayerStartTrackingEntity().initialize();
        modContainer.onTagsUpdated().initialize();
        modContainer.onServerStarted().initialize();
        modContainer.onServerStarting().initialize();
        modContainer.onServerStopped().initialize();
        modContainer.onServerStopping().initialize();
    }

    private void onRegisterEntityAttributes(BLibMod mod, EntityAttributeCreationEvent event) {
        getModContainer(mod).getEntityAttributeSupplierPairs()
            .forEach(pair -> event.put(pair.v1().get(), pair.v2().get().build()));
    }

    private void onRegisterEntitySpawnPlacements(BLibMod mod, RegisterSpawnPlacementsEvent event) {
        getModContainer(mod).getEntitySpawnDataEntries()
            .forEach(spawnData -> {
                if (spawnData.isPlacementDisabled()) {
                    return;
                }

                @SuppressWarnings("unchecked")
                var entityType = (EntityType<Mob>) spawnData.getEntityTypeHolder().get();
                var placementData = spawnData.getPlacementData();
                var placement = placementData.type();
                var heightMap = placementData.heightmapType();
                @SuppressWarnings("unchecked")
                var spawnPredicate = (SpawnPlacements.SpawnPredicate<Mob>) placementData.spawnPredicate();

                event.register(
                    entityType,
                    placement,
                    heightMap,
                    spawnPredicate,
                    RegisterSpawnPlacementsEvent.Operation.AND
                );
            });
    }

    private static void registerClientReloadListener(PreparableReloadListener preparableReloadListener) {
        if (BLibAPI.getDistributionType() != DistributionEnvironmentType.CLIENT) {
            return;
        }

        var mc = Minecraft.getInstance();

        if (mc == null) {
            return;
        }

        if (!(mc.getResourceManager() instanceof ReloadableResourceManager resourceManager)) {
            throw new RuntimeException("Client reload listener was initialized too early!");
        }

        resourceManager.registerReloadListener(preparableReloadListener);
    }
}
