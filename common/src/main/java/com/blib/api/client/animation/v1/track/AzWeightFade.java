package com.blib.api.client.animation.v1.track;

/** A track weight moving smoothly from one value to another over time (AzureLib 3.1.13 layering). */
public final class AzWeightFade {

    private boolean active;

    private double fromWeight;

    private double toWeight;

    private double lengthTicks;

    private double startTick = Double.NaN;

    /**
     * @param fromWeight  the weight now
     * @param toWeight    the weight to reach
     * @param lengthTicks how long to take
     */
    public void start(double fromWeight, double toWeight, double lengthTicks) {
        this.active = true;
        this.fromWeight = fromWeight;
        this.toWeight = toWeight;
        this.lengthTicks = lengthTicks;
        this.startTick = Double.NaN;
    }

    /** Stops the fade where it is. */
    public void cancel() {
        this.active = false;
    }

    /** @return whether a fade is running */
    public boolean isActive() {
        return active;
    }

    /**
     * @param nowTick the animator's current animation time
     * @return the weight for this moment
     */
    public double update(double nowTick) {
        if (!active) {
            return toWeight;
        }

        if (Double.isNaN(startTick)) {
            startTick = nowTick;
        }

        var progress = (nowTick - startTick) / lengthTicks;

        if (progress >= 1) {
            active = false;
            return toWeight;
        }

        return fromWeight + (toWeight - fromWeight) * Math.max(0, progress);
    }
}
