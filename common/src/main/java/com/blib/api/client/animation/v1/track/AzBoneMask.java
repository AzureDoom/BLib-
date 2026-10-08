package com.blib.api.client.animation.v1.track;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.blib.api.client.model.v1.AzBone;

/**
 * Limits a track to some bones (AzureLib 3.1.13 layering): {@link #only} the named bones and their children, or every
 * bone {@link #except} those. Bones outside the mask are skipped before their keyframes are evaluated.
 */
public final class AzBoneMask {

    /** Every bone - the default. */
    public static final AzBoneMask ALL = new AzBoneMask(Set.of(), false);

    private final Set<String> boneNames;

    private final boolean onlyNamed;

    private AzBoneMask(Set<String> boneNames, boolean onlyNamed) {
        this.boneNames = boneNames;
        this.onlyNamed = onlyNamed;
    }

    /**
     * @param boneNames the bones (with their children) the track may animate
     * @return the mask
     */
    public static AzBoneMask only(String... boneNames) {
        return only(List.of(boneNames));
    }

    /**
     * @param boneNames the bones (with their children) the track may animate
     * @return the mask
     */
    public static AzBoneMask only(Collection<String> boneNames) {
        return new AzBoneMask(Set.copyOf(boneNames), true);
    }

    /**
     * @param boneNames the bones (with their children) the track must not animate
     * @return the mask
     */
    public static AzBoneMask except(String... boneNames) {
        return except(List.of(boneNames));
    }

    /**
     * @param boneNames the bones (with their children) the track must not animate
     * @return the mask
     */
    public static AzBoneMask except(Collection<String> boneNames) {
        return new AzBoneMask(Set.copyOf(boneNames), false);
    }

    /** @return whether this is the all-bones mask, so callers can skip the check entirely */
    public boolean isAll() {
        return boneNames.isEmpty() && !onlyNamed;
    }

    /**
     * @param bone a bone
     * @return whether the track may animate it
     */
    public boolean includes(AzBone bone) {
        if (boneNames.isEmpty()) {
            return !onlyNamed;
        }

        var underNamedBone = false;

        for (var current = bone; current != null; current = current.getParent()) {
            if (boneNames.contains(current.getName())) {
                underNamedBone = true;
                break;
            }
        }

        return underNamedBone == onlyNamed;
    }
}
