package com.blib.internal.client.render.util;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.blib.api.client.model.v1.AzBone;
import com.blib.internal.client.model.GeoCube;
import com.blib.mod.BLib;

public class RenderUtil {

    private static final Matrix4f INVERT_SCRATCH = new Matrix4f();

    private static final Quaternionf X_QUATERNION_CACHE = new Quaternionf();

    private static final Quaternionf Y_QUATERNION_CACHE = new Quaternionf();

    private static final Quaternionf Z_QUATERNION_CACHE = new Quaternionf();

    public static void translateMatrixToBone(PoseStack poseStack, AzBone bone) {
        poseStack.translate(-bone.getPosX() / 16f, bone.getPosY() / 16f, bone.getPosZ() / 16f);
    }

    public static void rotateMatrixAroundBone(PoseStack poseStack, AzBone bone) {
        float rotX = bone.getRotX();
        float rotY = bone.getRotY();
        float rotZ = bone.getRotZ();

        if (rotX == 0 && rotY == 0 && rotZ == 0)
            return;

        // Same Z, then Y, then X order as three mulPose calls, in one pass over each matrix. A pure rotation keeps the
        // normal matrix orthonormal, so nothing needs renormalising.
        var last = poseStack.last();
        last.pose().rotateZYX(rotZ, rotY, rotX);
        last.normal().rotateZYX(rotZ, rotY, rotX);
    }

    public static void rotateMatrixAroundCube(PoseStack poseStack, GeoCube cube) {
        Vec3 rotation = cube.rotation();

        if (rotation.z() != 0f) {
            Z_QUATERNION_CACHE.identity().rotateZ((float) rotation.z());
            poseStack.mulPose(Z_QUATERNION_CACHE);
        }
        if (rotation.y() != 0f) {
            Y_QUATERNION_CACHE.identity().rotateY((float) rotation.y());
            poseStack.mulPose(Y_QUATERNION_CACHE);
        }
        if (rotation.x() != 0f) {
            X_QUATERNION_CACHE.identity().rotateX((float) rotation.x());
            poseStack.mulPose(X_QUATERNION_CACHE);
        }
    }

    public static void scaleMatrixForBone(PoseStack poseStack, AzBone bone) {
        poseStack.scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
    }

    public static void translateToPivotPoint(PoseStack poseStack, GeoCube cube) {
        Vec3 pivot = cube.pivot();
        poseStack.translate(pivot.x() / 16f, pivot.y() / 16f, pivot.z() / 16f);
    }

    public static void translateToPivotPoint(PoseStack poseStack, AzBone bone) {
        poseStack.translate(bone.getPivotX() / 16f, bone.getPivotY() / 16f, bone.getPivotZ() / 16f);
    }

    public static void translateAwayFromPivotPoint(PoseStack poseStack, GeoCube cube) {
        Vec3 pivot = cube.pivot();

        poseStack.translate(-pivot.x() / 16f, -pivot.y() / 16f, -pivot.z() / 16f);
    }

    public static void translateAwayFromPivotPoint(PoseStack poseStack, AzBone bone) {
        poseStack.translate(-bone.getPivotX() / 16f, -bone.getPivotY() / 16f, -bone.getPivotZ() / 16f);
    }

    public static void translateAndRotateMatrixForBone(PoseStack poseStack, AzBone bone) {
        translateToPivotPoint(poseStack, bone);
        rotateMatrixAroundBone(poseStack, bone);
    }

    public static void prepMatrixForBone(PoseStack poseStack, AzBone bone) {
        translateMatrixToBone(poseStack, bone);
        translateToPivotPoint(poseStack, bone);
        rotateMatrixAroundBone(poseStack, bone);
        scaleMatrixForBone(poseStack, bone);
        translateAwayFromPivotPoint(poseStack, bone);
    }

    public static void applyCubeInflation(PoseStack poseStack, GeoCube cube, float inflate) {
        applyCubeInflation(poseStack.last().pose(), cube, inflate);
    }

    /**
     * Scales {@code pose} in place around the centre of {@code cube}'s vertex bounds so each side grows by
     * {@code inflate}. Matrix form of {@link #applyCubeInflation(PoseStack, GeoCube, float)}, used by the model
     * renderer now that cube transforms are applied straight to a scratch matrix instead of the pose stack.
     *
     * @return {@code true} if the matrix was changed
     */
    public static boolean applyCubeInflation(Matrix4f pose, GeoCube cube, float inflate) {
        var size = cube.size();

        if (size.x() <= 0 && size.y() <= 0 && size.z() <= 0) {
            return false;
        }

        // Bounds are baked into the cube at load; this used to rescan every vertex on every call.
        var bounds = cube.bounds();

        if (bounds.isEmpty()) {
            return false;
        }

        var extentX = bounds.extentX();
        var extentY = bounds.extentY();
        var extentZ = bounds.extentZ();

        var scaleX = extentX > 0 ? (extentX + inflate) / extentX : 1f;
        var scaleY = extentY > 0 ? (extentY + inflate) / extentY : 1f;
        var scaleZ = extentZ > 0 ? (extentZ + inflate) / extentZ : 1f;

        pose.translate(bounds.centerX(), bounds.centerY(), bounds.centerZ())
            .scale(scaleX, scaleY, scaleZ)
            .translate(-bounds.centerX(), -bounds.centerY(), -bounds.centerZ());
        return true;
    }

    /**
     * Inverts {@code inputMatrix} and multiplies it by {@code baseMatrix}, returning the result as a new
     * {@link Matrix4f}. Neither argument is modified.
     */
    public static Matrix4f invertAndMultiplyMatrices(Matrix4f baseMatrix, Matrix4f inputMatrix) {
        return invertAndMultiplyMatrices(baseMatrix, inputMatrix, new Matrix4f());
    }

    /**
     * Allocation-free form of {@link #invertAndMultiplyMatrices(Matrix4f, Matrix4f)}: writes
     * {@code inverse(inputMatrix) * baseMatrix} into {@code dest} and returns it. {@code dest} may not be
     * {@code baseMatrix}. Render thread only (uses a shared scratch matrix).
     */
    public static Matrix4f invertAndMultiplyMatrices(Matrix4f baseMatrix, Matrix4f inputMatrix, Matrix4f dest) {
        INVERT_SCRATCH.set(inputMatrix).invert();
        return INVERT_SCRATCH.mul(baseMatrix, dest);
    }

    public static void faceRotation(PoseStack poseStack, Entity animatable, float partialTick) {
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTick, animatable.yRotO, animatable.getYRot()) - 90));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTick, animatable.xRotO, animatable.getXRot())));
    }

    /**
     * Returns a new {@link Matrix4f} equal to {@code matrix} with {@code vector} added to its translation (a
     * world-space translation: {@code T(vector) * matrix}). The original matrix is not modified.
     * <p>
     * This used to add an identity-plus-translation matrix to {@code matrix}, which moved the translation correctly
     * but also added 1 to every diagonal entry, corrupting the rotation/scale part (and the {@code w} of transformed
     * points). It now only touches the translation.
     */
    public static Matrix4f translateMatrix(Matrix4f matrix, Vector3f vector) {
        return new Matrix4f(matrix).translateLocal(vector);
    }

    /**
     * In-place form of {@link #translateMatrix}: adds {@code vector} to {@code matrix}'s translation and returns it. Use
     * this when you own the matrix, to avoid the extra allocation.
     */
    public static Matrix4f translateMatrixInPlace(Matrix4f matrix, Vector3f vector) {
        return matrix.translateLocal(vector);
    }

    @Nullable
    public static IntIntPair getTextureDimensions(ResourceLocation texture) {
        if (texture == null)
            return null;

        AbstractTexture originalTexture = null;
        Minecraft mc = Minecraft.getInstance();

        try {
            originalTexture = mc.submit(() -> mc.getTextureManager().getTexture(texture)).get();
        } catch (Exception e) {
            BLib.LOGGER.warn("Failed to load image for id {}", texture);
            e.printStackTrace();
        }

        if (originalTexture == null)
            return null;

        NativeImage image = null;

        try {
            image = originalTexture instanceof DynamicTexture dynamicTexture
                ? dynamicTexture.getPixels()
                : NativeImage.read(mc.getResourceManager().getResource(texture).get().open());
        } catch (Exception e) {
            BLib.LOGGER.error("Failed to read image for id {}", texture);
            e.printStackTrace();
        }

        return image == null ? null : IntIntImmutablePair.of(image.getWidth(), image.getHeight());
    }

    public static double getCurrentSystemTick() {
        return System.nanoTime() / 1E6 / 50d;
    }

    public static double getCurrentTick() {
        return Blaze3D.getTime() * 20d;
    }

    public static float booleanToFloat(boolean input) {
        return input ? 1f : 0f;
    }

    public static Vec3 arrayToVec(double[] array) {
        return new Vec3(array[0], array[1], array[2]);
    }

    public static void matchModelPartRot(ModelPart from, AzBone to) {
        to.updateRotation(-from.xRot, -from.yRot, from.zRot);
    }

    /**
     * If a {@link GeoCube} is a 2d plane the quad's normal is inverted in an intersecting plane, which can cause issues
     * with shaders and other lighting tasks. This performs a pseudo-ABS function to help resolve some of those issues.
     */
    public static void fixInvertedFlatCube(GeoCube cube, Vector3f normal) {
        fixInvertedFlatCube(cube.normalFlips(), normal);
    }

    /**
     * {@link #fixInvertedFlatCube(GeoCube, Vector3f)} with the cube's flip flags already worked out; see
     * {@link GeoCube#normalFlips()}.
     */
    public static void fixInvertedFlatCube(int normalFlips, Vector3f normal) {
        if (normalFlips == 0)
            return;

        if (normal.x() < 0 && (normalFlips & GeoCube.FLIP_X) != 0)
            normal.mul(-1, 1, 1);

        if (normal.y() < 0 && (normalFlips & GeoCube.FLIP_Y) != 0)
            normal.mul(1, -1, 1);

        if (normal.z() < 0 && (normalFlips & GeoCube.FLIP_Z) != 0)
            normal.mul(1, 1, -1);
    }

    public static float getDirectionAngle(Direction direction) {
        return switch (direction) {
            case SOUTH -> 90f;
            case NORTH -> 270f;
            case EAST -> 180f;
            default -> 0f;
        };
    }
}
