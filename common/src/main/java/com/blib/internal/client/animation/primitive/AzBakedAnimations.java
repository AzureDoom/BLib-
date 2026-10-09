package com.blib.internal.client.animation.primitive;

import com.blib.mod.BLib;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import com.blib.internal.client.animation.cache.AzBakedAnimationCache;
import com.blib.internal.common.exception.AzureLibException;

public record AzBakedAnimations(
    Map<String, AzBakedAnimation> animations,
    Map<String, ResourceLocation> includes
) {

    @Nullable
    public AzBakedAnimation getAnimation(String name) {
        AzBakedAnimation result = animations.get(name);

        if (result != null || includes == null)
            return result;

        ResourceLocation otherFileID = includes.get(name);

        if (otherFileID == null)
            return null;

        AzBakedAnimations otherBakedAnims = AzBakedAnimationCache.getInstance().getOrNull(otherFileID);

        if (otherBakedAnims == null) {
            BLib.LOGGER.error(
                    "Animation '{}' is included from '{}', but that file is missing or failed to load",
                    name,
                    otherFileID
            );
            return null;
        }

        if (otherBakedAnims == this) {
            throw new AzureLibException(
                    "The animation file '" + otherFileID + "' refers back to itself through includes."
            );
        }

        return otherBakedAnims.getAnimationWithoutIncludes(name);
    }

    private AzBakedAnimation getAnimationWithoutIncludes(String name) {
        return animations.get(name);
    }

}
