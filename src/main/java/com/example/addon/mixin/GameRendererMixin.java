package com.example.addon.mixin;

import com.example.addon.modules.SydneyAspectRatio;
import com.example.addon.modules.ViewModel;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    // Meteor's own mixin reads a local named "projectionMatrix" in renderLevel,
    // so we change that value right after it is created.
    @ModifyVariable(method = "renderLevel", at = @At("STORE"), name = "projectionMatrix")
    private Matrix4f sydney$aspect(Matrix4f original) {
        SydneyAspectRatio module = Modules.get().get(SydneyAspectRatio.class);
        if (module == null || !module.isActive()) return original;

        var window = Minecraft.getInstance().getWindow();
        float windowAspect = (float) window.getWidth() / (float) window.getHeight();
        float wanted = module.ratio.get().floatValue();
        if (wanted <= 0f || windowAspect <= 0f) return original;

        // Copy first so a reused matrix is never scaled twice.
        Matrix4f copy = new Matrix4f(original);

        // m00 = 1 / (aspect * tan(fov / 2)); rescale it from the window's aspect to the wanted one.
        return copy.m00(copy.m00() * windowAspect / wanted);
    }

    // Hand FOV: the 3rd argument (index 2) of hudProjection.setupPerspective(near, far, fov, width, height)
    @ModifyArg(method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V"),
        index = 2)
    private float viewModel$fov(float original) {
        ViewModel viewModel = Modules.get().get(ViewModel.class);
        if (viewModel == null || !viewModel.isActive() || !viewModel.viewModelFov.get()) return original;

        return viewModel.fovAmount.get();
    }
}
