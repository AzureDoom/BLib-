package com.blib.internal.client.territory.compat.xaero;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.ApiStatus;
import xaero.map.WorldMapSession;
import xaero.map.highlight.ChunkHighlighter;
import xaero.map.highlight.HighlighterRegistry;

import java.awt.Color;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.blib.internal.client.faction.ClientFactionCache;
import com.blib.internal.client.territory.ClientTerritoryCache;

@ApiStatus.Internal
public class BLibChunkHighlighter extends ChunkHighlighter {

    private static final int FILL_OPACITY = 100;

    private static final int BORDER_OPACITY = 200;

    private static final int CONTESTED_COLOR = packColor(0xFF0000);

    private static final Map<ResourceLocation, Optional<int[]>> OVERLAY_TEXTURE_CACHE = new HashMap<>();

    public BLibChunkHighlighter() {
        super(true);
    }

    public static void register(HighlighterRegistry registry) {
        registry.register(new BLibChunkHighlighter());
    }

    public static void invalidateAll() {
        OVERLAY_TEXTURE_CACHE.clear();
        Minecraft.getInstance().tell(() -> {
            var session = WorldMapSession.getCurrentSession();

            if (session == null) {
                return;
            }

            var mapWorld = session.getMapProcessor().getMapWorld();

            if (mapWorld == null) {
                return;
            }

            var dimension = mapWorld.getCurrentDimension();

            if (dimension == null) {
                return;
            }

            dimension.getHighlightHandler().clearCachedHashes();
        });
    }

    public static void invalidateChunk(int chunkX, int chunkZ) {
        Minecraft.getInstance().tell(() -> {
            var session = WorldMapSession.getCurrentSession();

            if (session == null) {
                return;
            }

            var mapWorld = session.getMapProcessor().getMapWorld();

            if (mapWorld == null) {
                return;
            }

            var dimension = mapWorld.getCurrentDimension();

            if (dimension == null) {
                return;
            }

            var regionX = chunkX >> 5;
            var regionZ = chunkZ >> 5;

            dimension.getHighlightHandler().clearCachedHash(regionX, regionZ);
        });
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> dimension, int regionX, int regionZ) {
        // ⚠ Aug 28 — the blibClaimMapOverlay gamerule, synced to the client, gates the whole overlay here.
        if (!com.blib.internal.client.territory.BLibClaimHud.isClaimMapOverlayEnabled()) {
            return false;
        }
        var cache = ClientTerritoryCache.INSTANCE;
        var dimensionId = dimension.location();
        var startX = regionX * 32;
        var startZ = regionZ * 32;

        for (var x = startX; x < startX + 32; x++) {
            for (var z = startZ; z < startZ + 32; z++) {
                if (cache.isClaimed(dimensionId, new ChunkPos(x, z))) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> dimension, int x, int z) {
        if (!com.blib.internal.client.territory.BLibClaimHud.isClaimMapOverlayEnabled()) {
            return false;
        }
        return ClientTerritoryCache.INSTANCE.isClaimed(dimension.location(), new ChunkPos(x, z));
    }

    @Override
    public int[] getChunkHighlitColor(ResourceKey<Level> dimension, int x, int z) {
        var cache = ClientTerritoryCache.INSTANCE;
        var dimensionId = dimension.location();
        var factionIds = cache.getFactionIds(dimensionId, new ChunkPos(x, z));

        if (factionIds.size() == 1) {
            var texture = overlayTextureFromFaction(factionIds.getFirst());
            var pixels = texture == null ? Optional.<int[]>empty() : overlayPixels(texture);

            if (pixels.isPresent()) {
                System.arraycopy(pixels.get(), 0, resultStore, 0, 256);
                return resultStore;
            }
        }

        return super.getChunkHighlitColor(dimension, x, z);
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dimension, int x, int z) {
        var cache = ClientTerritoryCache.INSTANCE;
        var dimensionId = dimension.location();
        var pos = new ChunkPos(x, z);
        var factionIds = cache.getFactionIds(dimensionId, pos);

        if (factionIds.isEmpty()) {
            return null;
        }

        if (factionIds.size() > 1) {
            var fillOpacity = contestedBlinkPhase() ? 180 : 35;
            var edgeOpacity = contestedBlinkPhase() ? 255 : 90;
            var fill = (CONTESTED_COLOR & 0xFFFFFF00) | fillOpacity;
            var edge = (CONTESTED_COLOR & 0xFFFFFF00) | edgeOpacity;

            resultStore[0] = fill;
            resultStore[1] = edge;
            resultStore[2] = edge;
            resultStore[3] = edge;
            resultStore[4] = edge;

            return resultStore;
        }

        var primaryFaction = factionIds.getFirst();
        var rgb = colorFromFaction(primaryFaction);
        var packed = packColor(rgb);
        var fill = (packed & 0xFFFFFF00) | FILL_OPACITY;
        var edge = (packed & 0xFFFFFF00) | BORDER_OPACITY;

        resultStore[0] = fill;
        resultStore[1] = sameOwner(cache, dimensionId, x, z - 1, primaryFaction) ? fill : edge;
        resultStore[2] = sameOwner(cache, dimensionId, x + 1, z, primaryFaction) ? fill : edge;
        resultStore[3] = sameOwner(cache, dimensionId, x, z + 1, primaryFaction) ? fill : edge;
        resultStore[4] = sameOwner(cache, dimensionId, x - 1, z, primaryFaction) ? fill : edge;

        return resultStore;
    }

    @Override
    public int calculateRegionHash(ResourceKey<Level> dimension, int regionX, int regionZ) {
        var cache = ClientTerritoryCache.INSTANCE;
        var dimensionId = dimension.location();
        var startX = regionX * 32;
        var startZ = regionZ * 32;
        var hash = 0L;

        for (var x = startX; x < startX + 32; x++) {
            for (var z = startZ; z < startZ + 32; z++) {
                var factionIds = cache.getFactionIds(dimensionId, new ChunkPos(x, z));

                for (var factionId : factionIds) {
                    hash = hash * 37L + factionId.hashCode();
                    hash = hash * 37L + colorFromFaction(factionId);
                    hash = hash * 37L + overlayTextureHashFromFaction(factionId);
                }
                if (factionIds.size() > 1) {
                    hash = hash * 37L + (contestedBlinkPhase() ? 1 : 0);
                }

                hash = hash * 37L;
            }
        }

        return (int) (hash >> 32) * 37 + (int) (hash & 0xFFFFFFFFL);
    }

    @Override
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int x, int z) {
        var factionIds = ClientTerritoryCache.INSTANCE.getFactionIds(dimension.location(), new ChunkPos(x, z));

        if (factionIds.isEmpty()) {
            return Component.empty();
        }

        var playerOwnerName = ClientTerritoryCache.INSTANCE.getPlayerOwnerName(dimension.location(), new ChunkPos(x, z));

        if (factionIds.size() > 1) {
            return playerOwnerName.isBlank()
                ? Component.literal("CONTESTED")
                : Component.literal("CONTESTED - Claim: " + playerOwnerName);
        }

        return Component.literal(
            playerOwnerName.isBlank()
                ? "Claim: " + nameFromFaction(factionIds.getFirst())
                : "Claim: " + playerOwnerName
        );
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int x, int z) {
        return null;
    }

    @Override
    public void addMinimapBlockHighlightTooltips(
        List<Component> list,
        ResourceKey<Level> dimension,
        int x,
        int z,
        int width
    ) {}

    private static boolean sameOwner(ClientTerritoryCache cache, ResourceLocation dimension, int x, int z, ResourceLocation factionId) {
        var neighborFactions = cache.getFactionIds(dimension, new ChunkPos(x, z));

        if (neighborFactions.isEmpty()) {
            return false;
        }

        return neighborFactions.getFirst().equals(factionId);
    }

    private static String nameFromFaction(ResourceLocation factionId) {
        var metadata = ClientFactionCache.INSTANCE.get(factionId);

        if (metadata != null) {
            return metadata.name();
        }

        return factionId.toString();
    }

    public static boolean contestedBlinkPhase() {
        var level = Minecraft.getInstance().level;
        var gameTime = level == null ? 0L : level.getGameTime();

        return (gameTime / 10L) % 2L == 0L;
    }

    private static int colorFromFaction(ResourceLocation factionId) {
        var metadata = ClientFactionCache.INSTANCE.get(factionId);

        if (metadata != null) {
            return metadata.color();
        }

        var hash = factionId.hashCode();
        var hue = (hash & 0x7FFFFFFF) % 360 / 360.0f;

        return Color.HSBtoRGB(hue, 0.7f, 0.9f);
    }

    private static ResourceLocation overlayTextureFromFaction(ResourceLocation factionId) {
        var metadata = ClientFactionCache.INSTANCE.get(factionId);

        if (metadata == null || metadata.claimMapStyle() == null) {
            return null;
        }

        return metadata.claimMapStyle().overlayTexture();
    }

    private static int overlayTextureHashFromFaction(ResourceLocation factionId) {
        var texture = overlayTextureFromFaction(factionId);

        return texture == null ? 0 : texture.hashCode();
    }

    private static Optional<int[]> overlayPixels(ResourceLocation texture) {
        return OVERLAY_TEXTURE_CACHE.computeIfAbsent(texture, BLibChunkHighlighter::loadOverlayPixels);
    }

    private static Optional<int[]> loadOverlayPixels(ResourceLocation texture) {
        var resourceTexture = normalizeTextureLocation(texture);
        var resource = Minecraft.getInstance().getResourceManager().getResource(resourceTexture);

        if (resource.isEmpty()) {
            return Optional.empty();
        }

        try (var input = resource.get().open(); var image = NativeImage.read(input)) {
            var pixels = new int[256];
            var width = image.getWidth();
            var height = image.getHeight();

            for (var y = 0; y < 16; y++) {
                for (var x = 0; x < 16; x++) {
                    var sampleX = Math.min(width - 1, x * width / 16);
                    var sampleY = Math.min(height - 1, y * height / 16);
                    pixels[y * 16 + x] = packNativeImageColor(image.getPixelRGBA(sampleX, sampleY));
                }
            }

            return Optional.of(pixels);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static ResourceLocation normalizeTextureLocation(ResourceLocation texture) {
        var path = texture.getPath();

        if (path.startsWith("textures/") && path.endsWith(".png")) {
            return texture;
        }

        return ResourceLocation.fromNamespaceAndPath(texture.getNamespace(), "textures/" + path + ".png");
    }

    /**
     * How much of a claim overlay texture's own opacity survives onto the map.
     * <p>
     * ⭐ 0.4 = 60% MORE TRANSPARENT THAN THE ARTWORK. [stated] players want to see the terrain underneath the claim
     * veins rather than have them painted over. Applied here rather than in the art so it covers EVERY faction's
     * overlay texture, including ones added later, and so the source textures stay usable at full strength anywhere
     * else they are drawn.
     * </p>
     * <p>
     * ⚠ SCALES the texture's existing alpha, it does not replace it: a vein that was already semi-transparent stays
     * proportionally softer, and fully transparent pixels stay fully transparent instead of becoming a faint haze.
     * </p>
     */
    private static final float CLAIM_OVERLAY_OPACITY = 0.4F;

    private static int packNativeImageColor(int color) {
        var red = color & 0xFF;
        var green = (color >> 8) & 0xFF;
        var blue = (color >> 16) & 0xFF;
        var alpha = (color >> 24) & 0xFF;

        alpha = Math.round(alpha * CLAIM_OVERLAY_OPACITY);

        return (blue << 24) | (green << 16) | (red << 8) | alpha;
    }

    private static int packColor(int rgb) {
        var red = (rgb >> 16) & 0xFF;
        var green = (rgb >> 8) & 0xFF;
        var blue = rgb & 0xFF;

        return (blue << 24) | (green << 16) | (red << 8);
    }
}
