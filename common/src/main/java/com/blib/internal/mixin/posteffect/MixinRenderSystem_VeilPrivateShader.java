package com.blib.internal.mixin.posteffect;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

import com.blib.internal.client.posteffect.BLibIrisClassificationPass;
import com.blib.internal.client.posteffect.BLibVeilCompat;

/**
 * Oct 8 - while the classification pass runs with Veil having replaced the patched entity programs, swap each entity
 * shader for BLib's private copy right after it is set. Outside that pass this is one boolean read per setShader. See
 * {@link BLibVeilCompat}.
 */
@Mixin(RenderSystem.class)
public abstract class MixinRenderSystem_VeilPrivateShader {

    @Shadow
    private static ShaderInstance shader;

    @Inject(method = "setShader", at = @At("TAIL"))
    private static void blib$usePrivateEntityShader(Supplier<ShaderInstance> supplier, CallbackInfo ci) {
        if (!BLibIrisClassificationPass.isUsingPrivateShaders()) {
            return;
        }

        var current = shader;

        if (current != null && BLibVeilCompat.isEntityShader(current.getName())) {
            var copy = BLibVeilCompat.privateCopy(current);

            if (copy != null) {
                shader = copy;
            }
        }
    }
}
