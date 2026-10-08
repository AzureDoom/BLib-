package com.blib.internal.client.posteffect;

import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.HashSet;
import java.util.Set;

import com.blib.mod.BLib;

/**
 * Oct 7 - ONE MEASUREMENT BEFORE THE VEIL ENTITY FIX: WHICH PROGRAM IS REALLY DRAWING THE ENTITIES?
 * <p>
 * Under Veil (Sable) predator vision shows terrain but no entities. The Aug 29 conclusion was that Veil's shader
 * wrapping means the programs BLib patched (the ones with the extra entity outputs) are not the programs that actually
 * draw - but that was inferred from the symptom, never measured. The fix depends on the answer:
 * <ul>
 * <li>if the bound program is NOT BLib's patched one, entities must be classified with a dedicated BLib shader of our
 * own, which Veil has no reason to touch;</li>
 * <li>if it IS the patched program, the outputs are there and something else (draw buffers, write masks, the target
 * bound) is discarding them, which is a much smaller fix.</li>
 * </ul>
 * For each entity shader, the first time it draws, this logs: the program the ShaderInstance owns, the program GL
 * actually has bound, whether each one contains BLib's entity-mask output, the framebuffer being drawn into, and
 * whether draw buffer 1 is writable. One line per shader name, capped, then silent.
 * <p>
 * {@code -Dblib.veil.probe=true} only. Off by default: one boolean read per draw call. Any failure logs once and turns
 * the probe off. Changes nothing it measures.
 */
@ApiStatus.Internal
public final class BLibVeilProbe {

    private static final boolean ENABLED = Boolean.getBoolean("blib.veil.probe");

    /** Distinct shaders to report before going quiet. */
    private static final int MAX_REPORTS = 40;

    private static final int GL_CURRENT_PROGRAM = 35725;

    private static final int GL_DRAW_FRAMEBUFFER_BINDING = 36006;

    private static final int GL_DRAW_BUFFER1 = 34854;

    private static final int GL_COLOR_WRITEMASK = 3107;

    private static final Set<String> REPORTED = new HashSet<>();

    private static boolean failed;

    private static boolean headerLogged;

    private BLibVeilProbe() {
        throw new UnsupportedOperationException();
    }

    /** Called just before a buffered draw, with the shader already applied. */
    public static void onDraw() {
        if (!ENABLED || failed || REPORTED.size() >= MAX_REPORTS) {
            return;
        }

        try {
            var shader = RenderSystem.getShader();

            if (shader == null) {
                return;
            }

            var name = shader.getName();

            if (!(name.startsWith("rendertype_entity") || name.startsWith("rendertype_armor")) || !REPORTED.add(name)) {
                return;
            }

            if (!headerLogged) {
                headerLogged = true;
                BLib.LOGGER.info(
                    "[VeilProbe] veilLoaded={} shaderPackActive={} classificationPass={}",
                    BLibIrisCompat.isVeilLoaded(),
                    BLibIrisCompat.isShaderPackActive(),
                    BLibIrisClassificationPass.isInsidePass()
                );
            }

            var owned = shader.getId();
            var bound = GL11.glGetInteger(GL_CURRENT_PROGRAM);
            BLib.LOGGER.info(
                "[VeilProbe] {} ownedProgram={} boundProgram={} same={} maskOutInOwned={} maskOutInBound={} drawFbo={} drawBuffer1={} buffer1Writable={}",
                name,
                owned,
                bound,
                owned == bound,
                GL30.glGetFragDataLocation(owned, "blib_entityMask"),
                bound == 0 ? -2 : GL30.glGetFragDataLocation(bound, "blib_entityMask"),
                GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
                GL11.glGetInteger(GL_DRAW_BUFFER1),
                GL30.glGetBooleani(GL_COLOR_WRITEMASK, 1)
            );
        } catch (Throwable throwable) {
            failed = true;
            BLib.LOGGER.warn("[VeilProbe] disabled after an error: {}", throwable.toString());
        }
    }
}
