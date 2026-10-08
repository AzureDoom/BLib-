package com.blib.api.client.animation.v1.command.sequence;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

import com.blib.api.client.animation.v1.command.AzCommand;
import com.blib.api.client.animation.v1.command.AzTarget;

/**
 * Plays an {@link AzSequence} on one track and delivers its timed events - ported from AzureLib 3.1.13.
 * <p>
 * ⚠ BLib difference: BLib animation commands run on the CLIENT (AzCommand refuses server-side dispatch), so a player
 * made with {@link #forEntity}/{@link #forBlockEntity} belongs on the client, where it plays the animation and fires
 * events for client effects (sounds, particles). For server gameplay timing - "deal damage on frame 12" - make an
 * {@link #eventsOnly} player on the server with the SAME sequence: it sends nothing and just fires the events on the
 * server's clock, while the client animation is started the way the mod already syncs its animations.
 * <p>
 * {@link #tick} - called once per game tick by the owner - fires each event when its tick is reached. Events due at
 * tick 0 fire inside {@code play}. Calling {@code play} again from a listener stops the old sequence's remaining events
 * from firing.
 *
 * @param <T> the animatable type the commands are sent for
 */
public final class AzSequencePlayer<T> {

    private final String trackName;

    private final Consumer<AzCommand<T>> dispatcher;

    private final AzSequenceEventListener listener;

    private @Nullable AzSequence current;

    private int elapsedTicks;

    private int nextEventIndex;

    private int generation;

    private AzSequencePlayer(String trackName, Consumer<AzCommand<T>> dispatcher, AzSequenceEventListener listener) {
        this.trackName = Objects.requireNonNull(trackName, "trackName");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * @param entity    the entity whose animator plays the sequence
     * @param trackName the track to play it on
     * @param listener  receives the events
     * @param <E>       the entity type
     * @return a player that sends its commands for that entity
     */
    public static <E extends Entity> AzSequencePlayer<E> forEntity(
        E entity,
        String trackName,
        AzSequenceEventListener listener
    ) {
        Objects.requireNonNull(entity, "entity");
        return new AzSequencePlayer<>(trackName, command -> command.dispatchForEntity(entity), listener);
    }

    /**
     * @param blockEntity the block entity whose animator plays the sequence
     * @param trackName   the track to play it on
     * @param listener    receives the events
     * @param <B>         the block entity type
     * @return a player that sends its commands for that block entity
     */
    public static <B extends BlockEntity> AzSequencePlayer<B> forBlockEntity(
        B blockEntity,
        String trackName,
        AzSequenceEventListener listener
    ) {
        Objects.requireNonNull(blockEntity, "blockEntity");
        return new AzSequencePlayer<>(trackName, command -> command.dispatchForBlockEntity(blockEntity), listener);
    }

    /**
     * @param trackName  the track to play it on
     * @param dispatcher sends the commands
     * @param listener   receives the events
     * @param <T>        the animatable type
     * @return a player with a custom dispatcher
     */
    public static <T> AzSequencePlayer<T> of(
        String trackName,
        Consumer<AzCommand<T>> dispatcher,
        AzSequenceEventListener listener
    ) {
        return new AzSequencePlayer<>(trackName, dispatcher, listener);
    }

    /**
     * @param listener receives the events
     * @param <T>      the animatable type (unused - nothing is sent)
     * @return a player that only keeps the event clock, for server-side gameplay timing
     */
    public static <T> AzSequencePlayer<T> eventsOnly(AzSequenceEventListener listener) {
        return new AzSequencePlayer<>("events_only", command -> {}, listener);
    }

    /**
     * Cancels the track, plays the sequence from its first stage and restarts the event clock.
     *
     * @param sequence the sequence to play
     */
    public void play(AzSequence sequence) {
        Objects.requireNonNull(sequence, "sequence");

        var target = AzTarget.track(trackName);
        dispatcher.accept(AzCommand.<T>builder().cancel(target).playSequence(target, sequence).build());

        this.current = sequence;
        this.elapsedTicks = 0;
        this.nextEventIndex = 0;
        this.generation++;

        fireDueEvents();
    }

    /** Cancels the track and forgets the sequence; no further events fire. */
    public void cancel() {
        if (current == null) {
            return;
        }

        dispatcher.accept(AzCommand.<T>builder().cancel(AzTarget.track(trackName)).build());
        clear();
    }

    /** Forgets the sequence without sending anything; no further events fire. */
    public void clear() {
        this.current = null;
        this.elapsedTicks = 0;
        this.nextEventIndex = 0;
        this.generation++;
    }

    /** Advances the event clock by one tick and fires any events now due. Call once per game tick. */
    public void tick() {
        if (current == null) {
            return;
        }

        elapsedTicks++;
        fireDueEvents();
    }

    private void fireDueEvents() {
        var sequence = current;

        if (sequence == null) {
            return;
        }

        var events = sequence.events();
        var startGeneration = generation;

        while (generation == startGeneration && nextEventIndex < events.size()) {
            var event = events.get(nextEventIndex);

            if (event.tick() > elapsedTicks) {
                break;
            }

            nextEventIndex++;
            listener.onEvent(sequence, event);
        }
    }

    /** @return the sequence playing, or null */
    public @Nullable AzSequence current() {
        return current;
    }

    /**
     * @param sequence a sequence to compare
     * @return whether that sequence is the one playing
     */
    public boolean isCurrent(AzSequence sequence) {
        return current != null && current.equals(sequence);
    }

    /** @return whether events are still to fire */
    public boolean hasPendingEvents() {
        return current != null && nextEventIndex < current.events().size();
    }

    /** @return ticks since the sequence started */
    public int elapsedTicks() {
        return elapsedTicks;
    }

    /** @return the track it plays on */
    public String trackName() {
        return trackName;
    }
}
