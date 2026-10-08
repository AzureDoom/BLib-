package com.blib.api.client.animation.v1.command.sequence;

/** Receives the timed events of a playing {@link AzSequence} - ported from AzureLib 3.1.13. */
@FunctionalInterface
public interface AzSequenceEventListener {

    /** A listener that ignores every event. */
    AzSequenceEventListener NONE = (sequence, event) -> {};

    /**
     * Called when an event's tick is reached.
     *
     * @param sequence the sequence that is playing
     * @param event    the event that is due
     */
    void onEvent(AzSequence sequence, AzSequenceEvent event);
}
