package com.blib.internal.client.render.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.UUID;

import com.blib.api.client.model.v1.AzBone;
import com.blib.api.client.render.v1.AzModelRenderer;
import com.blib.api.client.render.v1.AzRendererPipelineContext;
import com.blib.api.client.render.v1.item.pipeline.AzItemRendererPipelineContext;
import com.blib.internal.client.render.util.RenderUtil;

/**
 * Substitutes the player's skin arm for a {@code leftArm} / {@code rightArm} bone in a first-person item model.
 * <h2>⚠⚠ THE RIG BOX IS A STANDING ARM: SHOULDER AT THE TOP, HAND AT THE BOTTOM</h2> Vanilla's arm {@code ModelPart} is
 * authored in vanilla's Y-DOWN entity space (hand at local {@code +10}). This class used to draw that part straight
 * into the item model's Y-UP frame with only a scale and a shift, so the skin came out UPSIDE DOWN relative to the
 * modeller's box — hand at the top, shoulder at the bottom. AzureLib's own {@code gunwitharm} example only looks right
 * because every arm keyframe in it carries a {@code -180} Z roll to hide that. Vanilla's own entity renderer turns the
 * part over with {@code scale(-1,-1,1)} — a 180-degree turn about Z — and that is exactly what
 * {@link #applySkinArmTransform} now does, through the centre of the arm box.
 * <p>
 * ⚠⚠ ABOUT Z, NOT X. The first version turned about X. Both put the hand at the bottom, but they differ by a 180-degree
 * ROLL along the arm: X left the arm's inner face pointing outward and its front facing the camera, so a gauntlet that
 * sat on top of the arm in first person came out UNDERNEATH it in third person, where vanilla's Z flip is in force. Z
 * matches vanilla on every face: hand down, front forward, outer face outward.
 * <p>
 * With that in place a rig authored in Blockbench reads as marked: rotate the BOTTOM of the box toward -Z and the
 * skin's fist points forward. Nothing in the clips has to compensate.
 * <h2>⚠ {@link #rigArmMatrix} is the single source of truth</h2> Anything that wants to place geometry relative to the
 * skin arm outside this renderer — a third-person layer parenting a worn item to the real arm — must invert exactly the
 * transform used here, so it is exposed rather than duplicated. Change the fudge in one place and both views move
 * together.
 */
public class AzItemArmRenderUtil {

    private static final String LEFT_ARM_BONE = "leftArm";

    private static final String RIGHT_ARM_BONE = "rightArm";

    /**
     * ⚠ The scale that squeezes vanilla's 4x12x4 arm into the rig's 3x16x3 box, and the shift that centres it on the
     * bone. Measured against the box, not chosen: 0.67 * 4 = 2.68 wide, 1.33 * 12 = 16 tall.
     */
    private static final float ARM_SCALE_XZ = 0.67F;

    private static final float ARM_SCALE_Y = 1.33F;

    private static final float ARM_SHIFT_X = 0.25F;

    private static final float ARM_SHIFT_Y = -0.43625F;

    private static final float ARM_SHIFT_Z = 0.1625F;

    /**
     * Centre of vanilla's arm box in the part's own space: cube {@code y -2..10} centres on {@code 4}; {@code x -1..3}
     * (left) / {@code -3..1} (right) centres on {@code +1} / {@code -1}; {@code z} on 0.
     */
    private static final float ARM_BOX_CENTRE_X = 1.0F / 16.0F;

    private static final float ARM_BOX_CENTRE_Y = 4.0F / 16.0F;

    public static boolean isArmBone(AzBone bone) {
        var name = bone.getName();
        return LEFT_ARM_BONE.equals(name) || RIGHT_ARM_BONE.equals(name);
    }

    public static boolean isLeftArmBone(AzBone bone) {
        return LEFT_ARM_BONE.equals(bone.getName());
    }

    public static boolean shouldRenderArmsForContext(AzItemRendererPipelineContext context) {
        var transformType = context.getTransformType();
        return transformType == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND ||
            transformType == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
    }

    /**
     * Applies everything between the arm bone's frame and vanilla's arm {@code ModelPart}, in this order: the size
     * fudge, the centring shift, and the 180-degree turn about Z through the box's centre that puts the hand at the
     * BOTTOM of the rig box (vanilla's own entity flip). The part's own {@code setPos(pivot)} translate is NOT included
     * — the part applies that itself in {@code render}; callers building a full matrix add it, see
     * {@link #rigArmMatrix}.
     */
    public static void applySkinArmTransform(PoseStack poseStack, AzBone bone, boolean leftArm) {
        poseStack.scale(ARM_SCALE_XZ, ARM_SCALE_Y, ARM_SCALE_XZ);
        poseStack.translate(leftArm ? -ARM_SHIFT_X : ARM_SHIFT_X, ARM_SHIFT_Y, ARM_SHIFT_Z);

        // ⚠⚠ THE TURN. Rotate about Z through the centre of the arm box (pivot + box centre, in this frame) so the
        // part flips end for end without moving: shoulder to the top of the rig box, hand to the bottom, outer face
        // still outward. A proper rotation, not a Y reflection — a reflection would mirror the sleeve and flip every
        // face's winding. See the class doc for why Z and not X.
        var centreX = bone.getPivotX() / 16.0F + (leftArm ? ARM_BOX_CENTRE_X : -ARM_BOX_CENTRE_X);
        var centreY = bone.getPivotY() / 16.0F + ARM_BOX_CENTRE_Y;
        var centreZ = bone.getPivotZ() / 16.0F;

        poseStack.translate(centreX, centreY, centreZ);
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
        poseStack.translate(-centreX, -centreY, -centreZ);
    }

    /**
     * {@return the full matrix from the item model's root frame to vanilla's arm part frame for this bone, as it is
     * CURRENTLY animated} That is: the bone's own transform (position, pivot, rotation, scale — exactly as
     * {@link #renderArmForBone} applies it), then {@link #applySkinArmTransform}, then the part's pivot translate.
     * <p>
     * ⚠ Only the bone itself is walked, not its parents. The arm rig convention is that the arm bones sit under
     * zero-pivot, un-animated grouping bones; if a rig ever animates an ancestor of {@code leftArm}, extend this.
     */
    public static Matrix4f rigArmMatrix(AzBone bone, boolean leftArm) {
        var scratch = new PoseStack();

        RenderUtil.translateMatrixToBone(scratch, bone);
        RenderUtil.translateToPivotPoint(scratch, bone);
        RenderUtil.rotateMatrixAroundBone(scratch, bone);
        RenderUtil.scaleMatrixForBone(scratch, bone);
        RenderUtil.translateAwayFromPivotPoint(scratch, bone);

        applySkinArmTransform(scratch, bone, leftArm);

        // ModelPart.translateAndRotate: translate(x/16, y/16, z/16) with the part rotation at zero.
        scratch.translate(bone.getPivotX() / 16.0F, bone.getPivotY() / 16.0F, bone.getPivotZ() / 16.0F);

        return new Matrix4f(scratch.last().pose());
    }

    public static void renderArmForBone(
        AzRendererPipelineContext<UUID, ItemStack> context,
        AzBone bone,
        AzModelRenderer<UUID, ItemStack> modelRenderer
    ) {
        var itemContext = (AzItemRendererPipelineContext) context;

        // Only render if this is a first-person context
        if (!shouldRenderArmsForContext(itemContext)) {
            return;
        }

        // Hide the arm bone but keep children visible
        bone.setHidden(true);
        bone.setChildrenHidden(false);

        // ⚠ A ZERO-SCALED ARM BONE MEANS "NO ARM HERE". That is AzureLib's own convention (its gunwitharm clips
        // keyframe the spare arm's scale to 0) and it is how a one-armed item — a wrist gauntlet — hides the hand
        // that is not wearing it. Skip the draw entirely rather than emitting collapsed quads.
        if (bone.getScaleX() == 0.0F || bone.getScaleY() == 0.0F || bone.getScaleZ() == 0.0F) {
            return;
        }

        var client = Minecraft.getInstance();
        var poseStack = context.poseStack();
        var packedLight = context.packedLight();

        // Get player model and skin
        var playerEntityRenderer = (PlayerRenderer) client.getEntityRenderDispatcher().getRenderer(client.player);
        var playerEntityModel = playerEntityRenderer.getModel();
        var playerSkin = client.player.getSkin().texture();

        poseStack.pushPose();

        // Apply bone transformations
        RenderUtil.translateMatrixToBone(poseStack, bone);
        RenderUtil.translateToPivotPoint(poseStack, bone);
        RenderUtil.rotateMatrixAroundBone(poseStack, bone);
        RenderUtil.scaleMatrixForBone(poseStack, bone);
        RenderUtil.translateAwayFromPivotPoint(poseStack, bone);

        if (LEFT_ARM_BONE.equals(bone.getName())) {
            renderLeftArm(poseStack, bone, playerEntityModel, playerSkin, packedLight, itemContext, modelRenderer);
        } else if (RIGHT_ARM_BONE.equals(bone.getName())) {
            renderRightArm(poseStack, bone, playerEntityModel, playerSkin, packedLight, itemContext, modelRenderer);
        }

        poseStack.popPose();
    }

    private static void renderLeftArm(
        PoseStack poseStack,
        AzBone bone,
        PlayerModel<?> playerEntityModel,
        ResourceLocation playerSkin,
        int packedLight,
        AzItemRendererPipelineContext itemContext,
        AzModelRenderer<UUID, ItemStack> modelRenderer
    ) {
        applySkinArmTransform(poseStack, bone, true);

        // Set up and render the left arm
        playerEntityModel.leftArm.setPos(bone.getPivotX(), bone.getPivotY(), bone.getPivotZ());
        playerEntityModel.leftArm.setRotation(0, 0, 0);
        playerEntityModel.leftArm.render(
            poseStack,
            modelRenderer.getOrRefreshBufferRenderType(itemContext, bone, RenderType.entitySolid(playerSkin)),
            packedLight,
            OverlayTexture.NO_OVERLAY
        );

        // Set up and render a left sleeve
        playerEntityModel.leftSleeve.setPos(bone.getPivotX(), bone.getPivotY(), bone.getPivotZ());
        playerEntityModel.leftSleeve.setRotation(0, 0, 0);
        playerEntityModel.leftSleeve.render(
            poseStack,
            modelRenderer.getOrRefreshBufferRenderType(itemContext, bone, RenderType.entityTranslucent(playerSkin)),
            packedLight,
            OverlayTexture.NO_OVERLAY
        );
    }

    private static void renderRightArm(
        PoseStack poseStack,
        AzBone bone,
        PlayerModel<?> playerEntityModel,
        ResourceLocation playerSkin,
        int packedLight,
        AzItemRendererPipelineContext itemContext,
        AzModelRenderer<UUID, ItemStack> modelRenderer
    ) {
        applySkinArmTransform(poseStack, bone, false);

        // Set up and render right arm
        playerEntityModel.rightArm.setPos(bone.getPivotX(), bone.getPivotY(), bone.getPivotZ());
        playerEntityModel.rightArm.setRotation(0, 0, 0);
        playerEntityModel.rightArm.render(
            poseStack,
            modelRenderer.getOrRefreshBufferRenderType(itemContext, bone, RenderType.entitySolid(playerSkin)),
            packedLight,
            OverlayTexture.NO_OVERLAY
        );

        // Set up and render a right sleeve
        playerEntityModel.rightSleeve.setPos(bone.getPivotX(), bone.getPivotY(), bone.getPivotZ());
        playerEntityModel.rightSleeve.setRotation(0, 0, 0);
        playerEntityModel.rightSleeve.render(
            poseStack,
            modelRenderer.getOrRefreshBufferRenderType(itemContext, bone, RenderType.entityTranslucent(playerSkin)),
            packedLight,
            OverlayTexture.NO_OVERLAY
        );
    }
}
