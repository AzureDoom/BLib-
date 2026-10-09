package com.blib.internal.client.model;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Baked cuboid for an {@link com.blib.api.client.model.v1.AzBone}.
 * <p>
 * The cube's rotation around its pivot and the set of normal components that need flipping for flat cubes never change
 * after load, so both are worked out once here instead of for every cube on every frame.
 */
public record GeoCube(
    GeoQuad[] quads,
    Vec3 pivot,
    Vec3 rotation,
    Vec3 size,
    double inflate,
    boolean mirror,
    Transform transform,
    int normalFlips,
    Bounds bounds
) {

    /** {@link #normalFlips} bit: flip a negative X normal. */
    public static final int FLIP_X = 1;

    /** {@link #normalFlips} bit: flip a negative Y normal. */
    public static final int FLIP_Y = 2;

    /** {@link #normalFlips} bit: flip a negative Z normal. */
    public static final int FLIP_Z = 4;

    public GeoCube(GeoQuad[] quads, Vec3 pivot, Vec3 rotation, Vec3 size, double inflate, boolean mirror) {
        this(quads, pivot, rotation, size, inflate, mirror, Transform.of(pivot, rotation), normalFlipsFor(size));
    }

    public GeoCube(
        GeoQuad[] quads,
        Vec3 pivot,
        Vec3 rotation,
        Vec3 size,
        double inflate,
        boolean mirror,
        Transform transform,
        int normalFlips
    ) {
        this(quads, pivot, rotation, size, inflate, mirror, transform, normalFlips, Bounds.of(quads));
    }

    /**
     * Centre and half-extents of the cube's vertices, worked out once at load so runtime cube inflation
     * ({@code AzRendererPipelineContext#setCubeInflate}) doesn't rescan every vertex of every cube each frame.
     * {@link #EMPTY} for a cube without vertices.
     */
    public record Bounds(
        float centerX,
        float centerY,
        float centerZ,
        float extentX,
        float extentY,
        float extentZ
    ) {

        public static final Bounds EMPTY = new Bounds(0, 0, 0, 0, 0, 0);

        public static Bounds of(GeoQuad[] quads) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

            for (var quad : quads) {
                if (quad == null)
                    continue;

                for (var vertex : quad.vertices()) {
                    var pos = vertex.position();
                    minX = Math.min(minX, pos.x());
                    maxX = Math.max(maxX, pos.x());
                    minY = Math.min(minY, pos.y());
                    maxY = Math.max(maxY, pos.y());
                    minZ = Math.min(minZ, pos.z());
                    maxZ = Math.max(maxZ, pos.z());
                }
            }

            if (minX > maxX) {
                return EMPTY;
            }

            return new Bounds(
                (minX + maxX) / 2f,
                (minY + maxY) / 2f,
                (minZ + maxZ) / 2f,
                (maxX - minX) / 2f,
                (maxY - minY) / 2f,
                (maxZ - minZ) / 2f
            );
        }

        public boolean isEmpty() {
            return this == EMPTY;
        }
    }

    /**
     * Which normal components {@code RenderUtil#fixInvertedFlatCube} may flip for a cube of this size. Zero for any
     * cube with volume, which is most of them.
     */
    public static int normalFlipsFor(Vec3 size) {
        var flatX = size.x() == 0;
        var flatY = size.y() == 0;
        var flatZ = size.z() == 0;
        var flips = 0;

        if (flatY || flatZ)
            flips |= FLIP_X;

        if (flatX || flatZ)
            flips |= FLIP_Y;

        if (flatX || flatY)
            flips |= FLIP_Z;

        return flips;
    }

    /** A cube with a zero-length side is a single quad. */
    public boolean isFlat() {
        return size.x() == 0 || size.y() == 0 || size.z() == 0;
    }

    /**
     * A cube's rotation around its pivot, baked once at load:
     * {@code pose = T(pivot) * Rz * Ry * Rx * T(-pivot)} and {@code normal = Rz * Ry * Rx}, matching the order the
     * renderer used to apply them to the pose stack.
     *
     * @param pose     the cube-local transform, in model units (pixels / 16)
     * @param normal   the matching normal transform
     * @param identity {@code true} for an unrotated cube, which needs no transform at all
     */
    public record Transform(
        Matrix4fc pose,
        Matrix3fc normal,
        boolean identity
    ) {

        public static final Transform IDENTITY = new Transform(new Matrix4f(), new Matrix3f(), true);

        public static Transform of(Vec3 pivot, Vec3 rotation) {
            var rotX = (float) rotation.x();
            var rotY = (float) rotation.y();
            var rotZ = (float) rotation.z();

            if (rotX == 0f && rotY == 0f && rotZ == 0f) {
                return IDENTITY;
            }

            var pivotX = (float) pivot.x() / 16f;
            var pivotY = (float) pivot.y() / 16f;
            var pivotZ = (float) pivot.z() / 16f;

            var pose = new Matrix4f().translate(pivotX, pivotY, pivotZ)
                .rotateZ(rotZ)
                .rotateY(rotY)
                .rotateX(rotX)
                .translate(-pivotX, -pivotY, -pivotZ);
            var normal = new Matrix3f().rotateZ(rotZ).rotateY(rotY).rotateX(rotX);

            return new Transform(pose, normal, false);
        }
    }
}
