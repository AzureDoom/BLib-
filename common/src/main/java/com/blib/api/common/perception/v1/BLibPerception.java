package com.blib.api.common.perception.v1;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

/**
 * ⚠ Aug 28 — the perception registry backing the claim-awareness HUD. "Can this mob perceive this player" is a question
 * BLib cannot answer alone — avp_predator's cloak, future stealth systems — so consumers register predicates here and
 * the awareness poll asks every one of them. ALL predicates must agree the player is perceivable; a single false hides
 * them. With nothing registered, everyone is perceivable, which is vanilla behaviour.
 */
public final class BLibPerception {

    private static final List<BiPredicate<LivingEntity, Player>> PREDICATES = new CopyOnWriteArrayList<>();

    private BLibPerception() {}

    public static void register(BiPredicate<LivingEntity, Player> predicate) {
        PREDICATES.add(predicate);
    }

    public static boolean canPerceive(LivingEntity observer, Player player) {
        for (var predicate : PREDICATES) {
            if (!predicate.test(observer, player)) {
                return false;
            }
        }

        return true;
    }
}
