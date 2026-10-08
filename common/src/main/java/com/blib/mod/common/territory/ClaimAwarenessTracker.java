package com.blib.mod.common.territory;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.blib.api.common.faction.v1.RelationshipState;
import com.blib.api.common.perception.v1.BLibPerception;
import com.blib.internal.common.faction.BLibFactionManager;
import com.blib.mod.BLib;
import com.blib.mod.common.network.packet.S2CClaimHudEventPayload;
import com.blib.mod.common.registry.init.BLibGameRules;

/**
 * ⚠ Aug 28 — the server-side brain of the claim HUD, replacing the old every-10-ticks action-bar spam with
 * EDGE-TRIGGERED notices, exactly as he ruled it:
 * <ul>
 * <li>Entering a faction's territory: one "Entering X territory" notice, faction name coloured by standing — green
 * friendly, yellow neutral, red hostile — then it fades. Leaving: one "Leaving X territory". Crossing between two
 * claimed chunks of the SAME faction fires nothing.</li>
 * <li>Awareness tiers by standing: friendly territory stays silent; neutral territory says the faction is taking notice
 * when a member actually SEES the player; hostile territory says the faction is aware of the trespassing when seen or
 * targeted, as a flashing red title.</li>
 * <li>Re-arm is ESCAPE-BASED, never a timer (his ruling: timers are annoying): after a genuine escape — no member sees
 * the player and none is targeting them for a few seconds — the next detection fires again.</li>
 * <li>"Seen" = a member of that faction within {@link #SEEN_RANGE} blocks with line of sight, consulting
 * {@link BLibPerception} so a cloaked player is not seen by anything the cloak works on.</li>
 * </ul>
 * Polled once a second per player. The {@code blibClaimHudMessages} gamerule silences every notice;
 * {@code blibClaimMapOverlay} rides to the client in the settings sync and gates the map highlighter there.
 */
public final class ClaimAwarenessTracker {

    private static final int POLL_INTERVAL_TICKS = 20;

    private static final double SEEN_RANGE = 32.0;

    /** Three clean polls (seconds) with no sight and no aggro count as a genuine escape. */
    private static final int ESCAPE_POLLS = 3;

    private static final int COLOR_FRIENDLY = 0x55FF55;

    private static final int COLOR_NEUTRAL = 0xFFFF55;

    private static final int COLOR_HOSTILE = 0xFF5555;

    private static final Map<UUID, PlayerState> STATES = new HashMap<>();

    private ClaimAwarenessTracker() {}

    public static void tick(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getGameTime() % POLL_INTERVAL_TICKS != 0) {
            return;
        }

        for (var player : serverLevel.players()) {
            tickPlayer(serverLevel, player);
        }
    }

    private static void tickPlayer(ServerLevel level, ServerPlayer player) {
        var state = STATES.computeIfAbsent(player.getUUID(), uuid -> new PlayerState());
        var hudMessages = level.getGameRules().getBoolean(BLibGameRules.CLAIM_HUD_MESSAGES);
        var mapOverlay = level.getGameRules().getBoolean(BLibGameRules.CLAIM_MAP_OVERLAY);

        // Settings ride to the client on join and whenever a gamerule flips — the client cannot read gamerules.
        var settingsBits = (mapOverlay ? 1 : 0) | (hudMessages ? 2 : 0);

        if (settingsBits != state.lastSettingsBits) {
            state.lastSettingsBits = settingsBits;
            send(player, new S2CClaimHudEventPayload(S2CClaimHudEventPayload.KIND_SETTINGS, "", settingsBits));
        }

        var factionsHere = BLib.MOD.territory().getClaimants(level, new ChunkPos(player.blockPosition()));

        if (hudMessages) {
            for (var factionId : factionsHere) {
                if (!state.inside.contains(factionId)) {
                    send(player, event(S2CClaimHudEventPayload.KIND_ENTER, level, player, factionId));
                }
            }

            for (var factionId : state.inside) {
                if (!factionsHere.contains(factionId)) {
                    send(player, event(S2CClaimHudEventPayload.KIND_LEAVE, level, player, factionId));
                }
            }
        }

        state.inside.clear();
        state.inside.addAll(factionsHere);

        // Awareness only applies inside someone's territory, and never in friendly territory.
        // ⚠⚠ A CREATIVE OR SPECTATOR PLAYER IS NOT THERE AS FAR AS A FACTION IS CONCERNED. Mobs cannot target them
        // and nothing is ever dispatched at them, so reporting that a hive has 'taken note' of one is simply wrong -
        // and it reads exactly like a spawn trigger to anyone testing, which cost real time chasing a phantom.
        //
        // ⚠ The ENTER/LEAVE notifications above are left alone on purpose: knowing whose ground you are standing on
        // is useful in creative, and unlike this it makes no claim about being seen.
        if (player.isCreative() || player.isSpectator()) {
            return;
        }

        for (var factionId : factionsHere) {
            var standing = standingFor(level, player, factionId);

            if (standing == RelationshipState.ALLIED) {
                continue;
            }

            var seen = false;
            var targeted = false;
            var box = AABB.unitCubeFromLowerCorner(player.position()).inflate(SEEN_RANGE);

            for (var mob : level.getEntitiesOfClass(Mob.class, box)) {
                if (!BLibFactionManager.INSTANCE.getFactionIds(mob.getUUID()).contains(factionId)) {
                    continue;
                }

                if (mob.getTarget() == player) {
                    targeted = true;
                }

                if (BLibPerception.canPerceive(mob, player) && mob.hasLineOfSight(player)) {
                    seen = true;
                }

                if (seen && targeted) {
                    break;
                }
            }

            var detected = standing == RelationshipState.HOSTILE ? (seen || targeted) : seen;
            var armed = state.armed.getOrDefault(factionId, Boolean.TRUE);

            if (detected) {
                state.escapePolls.put(factionId, 0);

                if (armed && hudMessages) {
                    var kind = standing == RelationshipState.HOSTILE
                        ? S2CClaimHudEventPayload.KIND_AWARE
                        : S2CClaimHudEventPayload.KIND_NOTICE;
                    send(player, event(kind, level, player, factionId));
                    state.armed.put(factionId, Boolean.FALSE);
                }
            } else if (!armed) {
                // His re-arm rule: a genuine escape, not a timer — unseen AND unaggroed for a few clean polls.
                var polls = state.escapePolls.merge(factionId, 1, Integer::sum);

                if (polls >= ESCAPE_POLLS) {
                    state.armed.put(factionId, Boolean.TRUE);
                    state.escapePolls.put(factionId, 0);
                }
            }
        }
    }

    private static S2CClaimHudEventPayload event(int kind, ServerLevel level, ServerPlayer player, ResourceLocation factionId) {
        var faction = BLibFactionManager.INSTANCE.get(factionId);
        var name = faction != null ? faction.name() : factionId.getPath();
        var standing = standingFor(level, player, factionId);
        var color = switch (standing) {
            case ALLIED -> COLOR_FRIENDLY;
            case HOSTILE -> COLOR_HOSTILE;
            default -> COLOR_NEUTRAL;
        };

        return new S2CClaimHudEventPayload(kind, name, color);
    }

    /**
     * The player's standing with a faction. A faction the player belongs to, or one ALLIED with any of the player's
     * factions, is friendly; HOSTILE to any of them is hostile; a factionless player reads the faction's posture from
     * whether its members are targeting anyone — kept simple here: factionless defaults to the faction-to-faction
     * NEUTRAL, and the aware tier still fires if members target the player.
     */
    private static RelationshipState standingFor(ServerLevel level, ServerPlayer player, ResourceLocation factionId) {
        var playerFactions = BLibFactionManager.INSTANCE.getFactionIds(player.getUUID());

        if (playerFactions.contains(factionId)) {
            return RelationshipState.ALLIED;
        }

        var sawNeutral = false;

        for (var playerFaction : playerFactions) {
            var relation = BLibFactionManager.INSTANCE.getRelationship(playerFaction, factionId);

            if (relation == RelationshipState.ALLIED) {
                return RelationshipState.ALLIED;
            }

            if (relation == RelationshipState.HOSTILE) {
                return RelationshipState.HOSTILE;
            }

            sawNeutral = true;
        }

        return sawNeutral ? RelationshipState.NEUTRAL : RelationshipState.NEUTRAL;
    }

    private static void send(ServerPlayer player, S2CClaimHudEventPayload payload) {
        BLib.MOD.networking().sendToClient(player, payload);
    }

    public static void onPlayerDisconnect(UUID uuid) {
        STATES.remove(uuid);
    }

    private static final class PlayerState {

        private final Set<ResourceLocation> inside = new HashSet<>();

        private final Map<ResourceLocation, Boolean> armed = new HashMap<>();

        private final Map<ResourceLocation, Integer> escapePolls = new HashMap<>();

        private int lastSettingsBits = -1;
    }
}
