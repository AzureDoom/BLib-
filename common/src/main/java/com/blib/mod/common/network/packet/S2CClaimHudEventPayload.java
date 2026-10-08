package com.blib.mod.common.network.packet;

import com.just.codec.stream.RecordStreamCodec;
import com.just.codec.stream.StreamCodec;
import com.just.codec.stream.impl.StreamCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import com.blib.mod.BLib;

/**
 * ⚠ Aug 28 — one payload for the whole claim-HUD feature. kind: 0 = entering territory, 1 = leaving, 2 = the faction is
 * taking notice (neutral standing, seen), 3 = the faction is aware of your trespassing (hostile standing, seen or
 * targeted), 4 = settings sync (color bit0 = map overlay on, bit1 = hud messages on; factionName unused). Colors are
 * the standing colors his spec names: green friendly, yellow neutral, red hostile.
 */
public record S2CClaimHudEventPayload(
    int kind,
    String factionName,
    int color
) implements CustomPacketPayload {

    public static final ResourceLocation PAYLOAD_ID = BLib.MOD.resources().createLocation("claim_hud_event");

    public static final Type<S2CClaimHudEventPayload> TYPE = new Type<>(PAYLOAD_ID);

    public static final StreamCodec<S2CClaimHudEventPayload> CODEC = RecordStreamCodec.of(
        StreamCodecs.INT,
        S2CClaimHudEventPayload::kind,
        StreamCodecs.STRING_UTF8,
        S2CClaimHudEventPayload::factionName,
        StreamCodecs.INT,
        S2CClaimHudEventPayload::color,
        S2CClaimHudEventPayload::new
    );

    public static final int KIND_ENTER = 0;

    public static final int KIND_LEAVE = 1;

    public static final int KIND_NOTICE = 2;

    public static final int KIND_AWARE = 3;

    public static final int KIND_SETTINGS = 4;

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
