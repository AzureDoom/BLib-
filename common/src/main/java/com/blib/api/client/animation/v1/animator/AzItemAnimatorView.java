package com.blib.api.client.animation.v1.animator;

import net.minecraft.world.item.ItemDisplayContext;

/**
 * Identifies which of an {@link net.minecraft.world.item.ItemStack ItemStack}'s animators is being used. <br>
 * By default every item stack has a single animator ({@link #DEFAULT}) shared by every {@link ItemDisplayContext}. When
 * a renderer opts in with {@code AzItemRendererConfig.Builder#useSeparateFirstPersonAnimator()}, the first-person hand
 * contexts get their own animator ({@link #FIRST_PERSON}) with its own controllers, bone state and timer, so the
 * first-person and third-person views of the same stack can play different animations at the same time. <br>
 * <br>
 * The view being rendered is tracked per thread while an item renders, so anything that looks the stack's animator up
 * during that render (render layers, the model provider, custom pipeline code) gets the animator for the view being
 * drawn. Outside of rendering the current view is always {@link #DEFAULT}.
 * <p>
 * Ported from AzureLib (Azure, Oct 12 2026: "Add separate first-person item animators"). BLib dispatches item commands
 * client-side only, so AzureLib's network codec for this enum is not needed here.
 */
public enum AzItemAnimatorView {

    /**
     * The stack's main animator. Used for third person, GUI, ground, item frames, heads and every other context, and
     * for first person too unless the renderer uses a separate first-person animator.
     */
    DEFAULT,

    /**
     * The first-person animator, used for {@link ItemDisplayContext#FIRST_PERSON_LEFT_HAND} and
     * {@link ItemDisplayContext#FIRST_PERSON_RIGHT_HAND} when the renderer uses a separate first-person animator.
     */
    FIRST_PERSON;

    private static final ThreadLocal<AzItemAnimatorView> CURRENT = ThreadLocal.withInitial(() -> DEFAULT);

    /**
     * Maps a display context to a view: the first-person hand contexts go to {@link #FIRST_PERSON}, everything else to
     * {@link #DEFAULT}.
     */
    public static AzItemAnimatorView fromDisplayContext(ItemDisplayContext context) {
        return context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
            || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND ? FIRST_PERSON : DEFAULT;
    }

    /** @return the view being rendered on this thread, or {@link #DEFAULT} when no item is rendering. */
    public static AzItemAnimatorView current() {
        return CURRENT.get();
    }

    /**
     * Makes {@code view} the current view on this thread and returns the previous one, which must be handed back to
     * {@link #restore(AzItemAnimatorView)} once rendering is done (use try/finally).
     */
    public static AzItemAnimatorView push(AzItemAnimatorView view) {
        var previous = CURRENT.get();
        CURRENT.set(view);
        return previous;
    }

    public static void restore(AzItemAnimatorView previous) {
        CURRENT.set(previous);
    }
}
