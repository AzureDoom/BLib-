package com.blib.internal.client.posteffect;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL30;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.blib.mod.BLib;

/**
 * Oct 8 - THE VISION UNDER VEIL (Sable, and anything else that turns on Veil's dynamic buffers).
 * <h2>WHAT VEIL DOES - read from Veil's own source and confirmed by the probe</h2> BLib patches the vanilla entity
 * shaders as they compile, adding the classification outputs the vision reads. When a mod switches on Veil's dynamic
 * buffers, Veil RECOMPILES every vanilla shader from the original, unpatched files (VanillaShaderCompiler ->
 * veil$recompile -> veil$applyCompile) and swaps the new program in. The tester-equivalent probe log showed exactly
 * that: the program each entity draw used was the shader's own (same=true) but had no BLib output in it
 * (maskOutInOwned=-1). Veil also writes its own extra outputs to locations 1 and up - the same slots BLib uses - and
 * some entity draws go to Veil's own framebuffer. So the patched main-pass route cannot work while Veil does this.
 * <h2>THE FIX</h2> The classification pass (built for shader packs) draws the visible entities a second time into
 * BLib's own target. While Veil has replaced the patched programs, that pass binds BLib's PRIVATE copies of the entity
 * shaders instead - ShaderInstances BLib constructs itself, which link the original patched shader objects (still
 * alive: Veil only swaps the program) and which are not in the game's shader list, so Veil never recompiles them.
 * <h2>WHEN</h2> Decided by MEASUREMENT, not by which mods are present: Veil loaded AND a vanilla entity shader's live
 * program is missing the BLib output. Iris installed with no pack, Iris with a pack, or no Iris - all covered, because
 * the check looks at what the GPU actually has. Without Veil nothing here ever runs. Off switch:
 * {@code -Dblib.veil.fix=false}.
 */
@ApiStatus.Internal
public final class BLibVeilCompat {

    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("blib.veil.fix"));

    private static final boolean VEIL_LOADED = classExists("foundry.veil.Veil");

    /** The entity shaders the classification pass may need private copies of (the patcher's ENTITY family). */
    private static final Set<String> ENTITY_SHADERS = new HashSet<>();

    /** Private copies, by shader name, with the vanilla instance each was made alongside. */
    private static final Map<String, Copy> COPIES = new HashMap<>();

    /** Names whose private copy failed to build - not retried until the vanilla shader is rebuilt. */
    private static final Map<String, ShaderInstance> FAILED = new HashMap<>();

    private static int checkedProgram = -1;

    private static boolean patchMissing;

    private static boolean loggedState;

    private record Copy(
        ShaderInstance vanilla,
        ShaderInstance copy
    ) {}

    private BLibVeilCompat() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@return whether the classification pass should run with BLib's private entity shaders} True only when Veil is
     * loaded, the fix is on, and a vanilla entity shader's live program has lost the BLib output. Render thread.
     */
    public static boolean patchReplacedByVeil() {
        if (!ENABLED || !VEIL_LOADED) {
            return false;
        }

        var probe = GameRenderer.getRendertypeEntityCutoutShader();

        if (probe == null) {
            return false;
        }

        // Re-measured only when the program changes (Veil swaps it on recompile, a reload rebuilds it).
        var program = probe.getId();

        if (program != checkedProgram) {
            checkedProgram = program;
            patchMissing = GL30.glGetFragDataLocation(program, "blib_entityMask") < 0;

            if (!loggedState || patchMissing) {
                loggedState = true;
                BLib.LOGGER.info(
                    "[BLib] Veil present; entity shader program {} {} the BLib outputs - {}",
                    program,
                    patchMissing ? "is missing" : "has",
                    patchMissing ? "the vision will classify entities with private shaders" : "no workaround needed"
                );
            }
        }

        return patchMissing;
    }

    /** @return whether a shader is one the classification pass swaps for a private copy */
    public static boolean isEntityShader(String name) {
        return name.startsWith("rendertype_entity") || name.startsWith("rendertype_armor") || ENTITY_SHADERS.contains(name);
    }

    /**
     * {@return BLib's private copy of a vanilla entity shader, building it on first use, or null to keep the vanilla
     * one} A copy is rebuilt when the vanilla instance it was made alongside is replaced (resource reload), and the old
     * copy is closed so no GL program leaks.
     */
    public static @Nullable ShaderInstance privateCopy(ShaderInstance vanilla) {
        var name = vanilla.getName();
        var existing = COPIES.get(name);

        if (existing != null) {
            if (existing.vanilla() == vanilla) {
                return existing.copy();
            }

            existing.copy().close();
            COPIES.remove(name);
        }

        if (FAILED.get(name) == vanilla) {
            return null;
        }

        try {
            VertexFormat format = vanilla.getVertexFormat();
            var copy = new ShaderInstance(Minecraft.getInstance().getResourceManager(), name, format);

            if (GL30.glGetFragDataLocation(copy.getId(), "blib_entityMask") < 0) {
                // The private copy did not get the outputs either - no point binding it.
                copy.close();
                FAILED.put(name, vanilla);
                BLib.LOGGER.warn("[BLib] private copy of '{}' has no BLib outputs; keeping the vanilla shader", name);
                return null;
            }

            COPIES.put(name, new Copy(vanilla, copy));
            FAILED.remove(name);
            return copy;
        } catch (Exception exception) {
            FAILED.put(name, vanilla);
            BLib.LOGGER.warn("[BLib] could not build a private copy of '{}': {}", name, exception.toString());
            return null;
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, BLibVeilCompat.class.getClassLoader());
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }
}
