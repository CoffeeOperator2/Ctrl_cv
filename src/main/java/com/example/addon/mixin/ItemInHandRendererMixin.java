package com.example.addon.mixin;

import com.example.addon.modules.ViewModel;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @WrapMethod(method = "submitArmWithItem")
    private void viewModel$render(AbstractClientPlayer player, float partialTicks, float pitch, InteractionHand hand,
                                  float swingProgress, ItemStack stack, float equipProgress, PoseStack poseStack,
                                  SubmitNodeCollector collector, int light, Operation<Void> original) {
        ViewModel viewModel = Modules.get().get(ViewModel.class);

        if (viewModel == null || !viewModel.isActive()) {
            original.call(player, partialTicks, pitch, hand, swingProgress, stack, equipProgress, poseStack, collector, light);
            return;
        }

        float swing = viewModel.swing(hand, swingProgress);
        float equip = viewModel.instantSwap.get() ? 0f : equipProgress;

        poseStack.pushPose();
        try {
            viewModel.apply(poseStack, player, hand);
            original.call(player, partialTicks, pitch, hand, swing, stack, equip, poseStack, collector, light);
        } finally {
            poseStack.popPose();
        }
    }

    @WrapMethod(method = "applyEatTransform")
    private void viewModel$eat(PoseStack pose, float partialTicks, HumanoidArm arm, ItemStack stack, Player player,
                               Operation<Void> original) {
        ViewModel viewModel = Modules.get().get(ViewModel.class);

        if (viewModel == null || !viewModel.isActive()) {
            original.call(pose, partialTicks, arm, stack, player);
            return;
        }

        if (!viewModel.eating.get()) return;

        float m = viewModel.eatMultiplier.get().floatValue();
        if (m >= 1f) {
            original.call(pose, partialTicks, arm, stack, player);
            return;
        }
        if (m <= 0f) return;

        PoseStack scratch = new PoseStack();
        original.call(scratch, partialTicks, arm, stack, player);

        Matrix4f matrix = scratch.last().pose();
        Vector3f t = matrix.getTranslation(new Vector3f());
        Quaternionf r = matrix.getNormalizedRotation(new Quaternionf());

        pose.translate(t.x * m, t.y * m, t.z * m);
        pose.mulPose(new Quaternionf().slerp(r, m));
    }

    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
    private void viewModel$noSway(CallbackInfo ci) {
        ViewModel viewModel = Modules.get().get(ViewModel.class);
        LocalPlayer player = Minecraft.getInstance().player;

        if (viewModel == null || !viewModel.isActive() || !viewModel.noSway.get() || player == null) return;

        player.xBobO = player.xBob = player.getXRot();
        player.yBobO = player.yBob = player.getYRot();
    }

    @ModifyExpressionValue(method = "submitArmWithItem",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getUseAnimation()Lnet/minecraft/world/item/ItemUseAnimation;"))
    private ItemUseAnimation viewModel$trident(ItemUseAnimation original, @Local(argsOnly = true) ItemStack stack) {
        ViewModel viewModel = Modules.get().get(ViewModel.class);

        if (viewModel != null && viewModel.isActive() && viewModel.noTridentAnim.get() && stack.is(Items.TRIDENT)) {
            return ItemUseAnimation.NONE;
        }

        return original;
    }
}
