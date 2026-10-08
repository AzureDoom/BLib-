package com.blib.api.client.animation.v1.animator;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.blib.api.client.animation.v1.track.AzAnimationTrackContainer;
import com.blib.internal.client.animation.AzAnimationTimer;
import com.blib.internal.client.animation.cache.AzBakedAnimationCache;
import com.blib.internal.client.animation.cache.AzBoneCache;
import com.blib.internal.client.animation.primitive.AzBakedAnimation;
import com.blib.internal.common.molang.MolangQueries;
import com.blib.internal.common.molang.MolangVariableRef;
import com.blib.mod.BLib;

public abstract class AzAnimator<K, T> {

    private AzAnimationContext<T> currentContext;

    private final WeakHashMap<K, AzAnimationContext<T>> contextCache = new WeakHashMap<>();

    private final AzAnimationTrackContainer<T> animationTrackContainer;

    protected final AzAnimatorConfig config;

    public boolean reloadAnimations;

    /** Locations already reported missing, so the warning is one line rather than one per tick per entity. */
    private static final Set<ResourceLocation> MISSING_ANIMATION_FILES = ConcurrentHashMap.newKeySet();

    protected AzAnimator() {
        this(AzAnimatorConfig.defaultConfig());
    }

    protected AzAnimator(AzAnimatorConfig config) {
        this.animationTrackContainer = new AzAnimationTrackContainer<>();

        this.config = config;
    }

    public AzBoneCache createBoneCache() {
        return new AzBoneCache();
    }

    public AzAnimationTimer createAzAnimationTimer(AzAnimatorConfig config) {
        return new AzAnimationTimer(config);
    }

    public AzAnimationContext<T> getOrCreateContext(K uuid) {
        var ctx = contextCache.computeIfAbsent(
            uuid,
            a -> new AzAnimationContext<>(createBoneCache(), config, createAzAnimationTimer(config))
        );
        this.currentContext = ctx;
        return ctx;
    }

    public abstract void registerTracks(AzAnimationTrackContainer<T> animationTrackContainer);

    public abstract @NotNull ResourceLocation getAnimationLocation(T animatable);

    public void animate(T animatable, float partialTicks, boolean updateTimer) {
        this.currentContext.setAnimatable(animatable);

        var boneCache = this.currentContext.boneCache();
        var timer = this.currentContext.timer();

        if (updateTimer) {
            timer.tick();
        }

        preAnimationSetup(animatable, timer.getAnimTime(), partialTicks);

        if (!boneCache.isEmpty()) {

            for (var track : animationTrackContainer.getAll()) {
                track.update();
            }

            this.reloadAnimations = false;

            boneCache.update(this.currentContext);
        }

        setCustomAnimations(animatable, partialTicks);
    }

    public void animate(T animatable, float partialTicks) {
        this.animate(animatable, partialTicks, true);
    }

    protected void preAnimationSetup(T animatable, double animTime, float partialTicks) {
        applyMolangQueries(animatable, animTime, partialTicks);
    }

    /*
     * AzureLib 3.1.13 port (Oct 5) - zero garbage per frame. The queries are bound through references resolved once
     * (MolangVariableRef) to suppliers created once, which read the per-frame values from fields instead of capturing
     * them in new lambdas every frame. That re-binding used to allocate around 830 bytes per animated mob per frame.
     */
    private static final MolangVariableRef LIFE_TIME_REF = new MolangVariableRef(MolangQueries.LIFE_TIME);

    private static final MolangVariableRef ACTOR_COUNT_REF = new MolangVariableRef(MolangQueries.ACTOR_COUNT);

    private static final MolangVariableRef TIME_OF_DAY_REF = new MolangVariableRef(MolangQueries.TIME_OF_DAY);

    private static final MolangVariableRef MOON_PHASE_REF = new MolangVariableRef(MolangQueries.MOON_PHASE);

    /** The animation time of the current frame, read by {@link #lifetimeSupplier}. */
    private double molangAnimTime;

    private final java.util.function.DoubleSupplier lifetimeSupplier = () -> molangAnimTime / 20d;

    private final java.util.function.DoubleSupplier actorCountSupplier = () -> {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getEntityCount();
    };

    private final java.util.function.DoubleSupplier timeOfDaySupplier = () -> {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getDayTime() / 24000f;
    };

    private final java.util.function.DoubleSupplier moonPhaseSupplier = () -> {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getMoonPhase();
    };

    protected void applyMolangQueries(T animatable, double animTime, float partialTicks) {
        if (Minecraft.getInstance().level == null) {
            return;
        }

        this.molangAnimTime = animTime;
        LIFE_TIME_REF.setMemoized(lifetimeSupplier);
        ACTOR_COUNT_REF.setMemoized(actorCountSupplier);
        TIME_OF_DAY_REF.setMemoized(timeOfDaySupplier);
        MOON_PHASE_REF.setMemoized(moonPhaseSupplier);
    }

    public void setCustomAnimations(T animatable, float partialTicks) {}

    /**
     * {@return the baked clip, or null if the animation FILE or the clip inside it is absent}
     * <p>
     * ⚠⚠ THIS USED TO DEREFERENCE {@code getOrNull} WITHOUT CHECKING IT, and the method name says exactly why that was
     * wrong. A consumer whose {@code getAnimationLocation} points at a file that is missing, renamed or failed to parse
     * got a bare NullPointerException on the render thread, crashing the client and naming BLib as the culprit in the
     * report — with nothing in the message to say which file or which mod.
     * <p>
     * ⚠ A missing animation is a content bug in the consumer, not a reason to end the game. Returning null lets the
     * track skip it, and the warning below names the file and the clip so the real bug is one grep away. Logged ONCE
     * per location so a per-tick dispatch cannot flood the log.
     */
    public @Nullable AzBakedAnimation getAnimation(T animatable, String name) {
        var location = getAnimationLocation(animatable);
        var bakedAnimations = AzBakedAnimationCache.getInstance().getOrNull(location);

        if (bakedAnimations == null) {
            warnOnce(location, name);

            return null;
        }

        return bakedAnimations.getAnimation(name);
    }

    private static void warnOnce(ResourceLocation location, String name) {
        if (MISSING_ANIMATION_FILES.add(location)) {
            BLib.LOGGER.warn(
                "No baked animations for '{}' (first missing clip: '{}'). The file is absent, misnamed, or failed"
                    + " to parse. Animations from it will not play.",
                location,
                name
            );
        }
    }

    public AzAnimationContext<T> context() {
        return currentContext;
    }

    public AzAnimationTrackContainer<T> getAnimationTrackContainer() {
        return animationTrackContainer;
    }
}
