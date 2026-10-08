package com.blib.api.client.animation.v1.command.sequence;

/**
 * A named moment in an {@link AzSequence}, counted in ticks from the start of the sequence - ported from AzureLib
 * 3.1.13. Delivered by {@link AzSequencePlayer}, typically on the server, e.g. to deal damage on a specific frame of an
 * attack.
 *
 * @param name the event's name
 * @param tick ticks from the start of the sequence
 */
public record AzSequenceEvent(
    String name,
    int tick
) {

    /**
     * @param name a name to compare
     * @return whether this event has that name
     */
    public boolean is(String name) {
        return this.name.equals(name);
    }
}
