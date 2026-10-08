package com.blib.internal.mixin.posteffect;

import com.mojang.blaze3d.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.blib.internal.client.posteffect.BLibVeilProbe;

/**
 * Oct 7 - feeds {@link BLibVeilProbe}: every buffered draw passes through {@code VertexBuffer.draw()} after its shader
 * has been applied, so this is the point where the program GL really uses can be compared with the one BLib patched.
 * One boolean read per draw while {@code -Dblib.veil.probe} is off.
 */
@Mixin(VertexBuffer.class)
public abstract class MixinVertexBuffer_VeilProbe {

    @Inject(method = "draw", at = @At("HEAD"), require = 0)
    private void blib$veilProbe(CallbackInfo ci) {
        BLibVeilProbe.onDraw();
    }
}
