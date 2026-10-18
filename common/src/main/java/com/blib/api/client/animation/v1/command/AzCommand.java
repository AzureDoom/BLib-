package com.blib.api.client.animation.v1.command;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.blib.api.BLibAPI;
import com.blib.api.client.animation.v1.animator.AzAnimator;
import com.blib.api.client.animation.v1.command.policy.AzDispatchMode;
import com.blib.internal.client.animation.AzAnimatorAccessor;
import com.blib.internal.client.animation.dispatch.AzPendingDispatches;
import com.blib.internal.client.animation.dispatch.command.action.AzAction;

public record AzCommand<T>(List<AzAction<T>> actions) {

    public AzCommand {
        // Defensive copy: ensures the published action list is immutable and decoupled from any
        // mutable list the caller (typically AzCommandBuilder) may continue to hold.
        actions = List.copyOf(actions);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AzCommand.class);

    private static final String SERVER_SIDE_DISPATCH_MESSAGE =
        "AzCommand.dispatch() was called on the server for %s '%s'. "
            + "Animation commands only work client-side. "
            + "Use a data-synced flag or network packet to trigger animations from the server.";

    private static final String SERVER_SIDE_DISPATCH_MESSAGE_LOG = SERVER_SIDE_DISPATCH_MESSAGE.replaceAll("%s", "{}");

    /**
     * Returns a fresh command builder with no dispatch mode set. Useful for set-only commands (e.g. {@code setSpeed}
     * without any play action) or when the caller wants to set the mode explicitly via
     * {@link AzCommandBuilder#dispatchMode(AzDispatchMode)}. Adding a play action to a builder with no mode set throws.
     */
    public static <T> AzCommandBuilder<T> builder() {
        return new AzCommandBuilder<>();
    }

    /**
     * Returns a command builder with {@link AzDispatchMode#REPLAY} pre-set.
     */
    public static <T> AzCommandBuilder<T> replay() {
        return new AzCommandBuilder<T>().dispatchMode(AzDispatchMode.REPLAY);
    }

    /**
     * Returns a command builder with {@link AzDispatchMode#PLAY_IF_NOT_PLAYING} pre-set.
     */
    public static <T> AzCommandBuilder<T> idempotent() {
        return new AzCommandBuilder<T>().dispatchMode(AzDispatchMode.PLAY_IF_NOT_PLAYING);
    }

    /**
     * Returns a command builder with {@link AzDispatchMode#ENQUEUE} pre-set.
     */
    public static <T> AzCommandBuilder<T> enqueueing() {
        return new AzCommandBuilder<T>().dispatchMode(AzDispatchMode.ENQUEUE);
    }

    public static <T> AzCommand<T> compose(Collection<AzCommand<T>> commands) {
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("Attempted to compose an empty collection of commands.");
        } else if (commands.size() == 1) {
            return commands.iterator().next();
        }

        return new AzCommand<>(
            commands.stream()
                .flatMap(command -> command.actions().stream())
                .toList()
        );
    }

    @SafeVarargs
    public static <T> AzCommand<T> compose(AzCommand<T> first, AzCommand<T> second, AzCommand<T>... others) {
        var allCommands = new ArrayList<AzCommand<T>>();

        allCommands.add(first);
        allCommands.add(second);
        Collections.addAll(allCommands, others);

        return compose(allCommands);
    }

    public void dispatchForEntity(T entity) {
        if (entity instanceof Entity mcEntity && !validateClientSide(mcEntity.level(), "Entity", entity)) {
            return;
        }
        dispatch(entity);
    }

    public void dispatchForBlockEntity(T blockEntity) {
        if (blockEntity instanceof BlockEntity mcBlockEntity) {
            var level = mcBlockEntity.getLevel();

            if (level != null && !validateClientSide(level, "BlockEntity", blockEntity)) {
                return;
            }
        }

        dispatch(blockEntity);
    }

    /**
     * Dispatches to every animator the stack has. If the item's renderer uses a separate first-person animator
     * ({@code AzItemRendererConfig.Builder#useSeparateFirstPersonAnimator()}), both the first-person and third-person
     * views play it; use
     * {@link #dispatchForItem(Entity, Object, com.blib.api.client.animation.v1.animator.AzItemAnimatorView)} to target
     * only one of them.
     */
    public void dispatchForItem(Entity entity, T itemStack) {
        dispatchForItem(entity, itemStack, null);
    }

    /**
     * Oct 14 (AzureLib port) - dispatches to the animator for one view only, or to every animator the stack has when
     * {@code view} is null. {@code FIRST_PERSON} only has an effect when the item's renderer was built with
     * {@code useSeparateFirstPersonAnimator()}; {@code DEFAULT} is third person and every other context.
     */
    @SuppressWarnings("unchecked")
    public void dispatchForItem(
        Entity entity,
        T itemStack,
        com.blib.api.client.animation.v1.animator.@org.jetbrains.annotations.Nullable AzItemAnimatorView view
    ) {
        if (!(itemStack instanceof ItemStack stack)) {
            dispatch(itemStack);
            return;
        }

        if (!validateClientSide(entity.level(), "ItemStack", stack)) {
            return;
        }

        var uuid = stack.get(com.blib.mod.common.registry.init.BLibDataComponents.AZ_ID.get());

        // AzureLib 3.1.22 (ported Oct 17): dispatchOrQueue, not forEach - a view with no animator yet (a stack that has
        // not been drawn) keeps the command and gets it the moment its animator is created, instead of losing it.
        com.blib.internal.client.animation.cache.AzIdentifiableItemStackAnimatorCache.getInstance()
            .dispatchOrQueue(
                uuid,
                view,
                animator -> actions.forEach(
                    action -> action.handle(
                        (com.blib.api.client.animation.v1.animator.AzAnimator<?, T>) (com.blib.api.client.animation.v1.animator.AzAnimator<?, ?>) animator
                    )
                )
            );
    }

    /**
     * Shorthand for
     * {@link #dispatchForItem(Entity, Object, com.blib.api.client.animation.v1.animator.AzItemAnimatorView)} with
     * {@code FIRST_PERSON}.
     */
    public void dispatchForItemFirstPerson(Entity entity, T itemStack) {
        dispatchForItem(entity, itemStack, com.blib.api.client.animation.v1.animator.AzItemAnimatorView.FIRST_PERSON);
    }

    /**
     * Shorthand for
     * {@link #dispatchForItem(Entity, Object, com.blib.api.client.animation.v1.animator.AzItemAnimatorView)} with
     * {@code DEFAULT} (third person and every other context).
     */
    public void dispatchForItemThirdPerson(Entity entity, T itemStack) {
        dispatchForItem(entity, itemStack, com.blib.api.client.animation.v1.animator.AzItemAnimatorView.DEFAULT);
    }

    @SuppressWarnings("unchecked")
    private void dispatch(T animatable) {
        var animator = AzAnimatorAccessor.<Object, T>getOrNull(animatable);

        if (animator != null) {
            actions.forEach(action -> action.handle(animator));
            return;
        }

        // AzureLib 3.1.22 (ported Oct 17): no animator yet - the animatable has not been rendered. Hold the command and
        // replay it when AzProvider creates the animator, so the first rendered frame already has its animation
        // instead of the bind pose. See AzPendingDispatches for the cap and expiry BLib adds.
        AzPendingDispatches.enqueue(
            animatable,
            created -> actions.forEach(action -> action.handle((AzAnimator<?, T>) created))
        );
    }

    /**
     * @return {@code true} on the client. On the server it warns (throws in a dev environment) and returns
     *         {@code false}, and the caller stops there.
     *         <p>
     *         ⚠ Oct 17: stopping is new, and needed now that a missing animator QUEUES the command. Server-side objects
     *         never get an animator, so a server dispatch used to fall through harmlessly; with the queue it would park
     *         entries in the client-side pending maps from the SERVER thread - and in singleplayer a server ItemStack
     *         carries the same AZ_ID as its client copy, so its queued command would then play on the client, from a
     *         call that is supposed to do nothing.
     *         </p>
     */
    private boolean validateClientSide(Level level, String type, Object animatable) {
        if (level.isClientSide()) {
            return true;
        }

        if (BLibAPI.isDevelopmentEnvironment()) {
            throw new IllegalStateException(SERVER_SIDE_DISPATCH_MESSAGE.formatted(type, animatable));
        }

        LOGGER.warn(SERVER_SIDE_DISPATCH_MESSAGE_LOG, type, animatable);
        return false;
    }
}
