
package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;

public class ViewModel extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMain = settings.createGroup("Main Hand");
    private final SettingGroup sgOff = settings.createGroup("Off Hand");
    private final SettingGroup sgSwing = settings.createGroup("Swing");

    // ViewModel.java: add imports for IntSetting, then these settings

    private final SettingGroup sgMisc = settings.createGroup("Misc");

    public final Setting<Boolean> eating = sgMisc.add(new BoolSetting.Builder()
        .name("eating").description("Plays the first-person eating animation.")
        .defaultValue(true).build());

    public final Setting<Double> eatMultiplier = sgMisc.add(new DoubleSetting.Builder()
        .name("eat-multiplier").description("Scales the eating animation.")
        .defaultValue(1.0).range(0, 1).sliderRange(0, 1).visible(eating::get).build());

    public final Setting<Boolean> noSway = sgMisc.add(new BoolSetting.Builder()
        .name("no-sway").description("Removes the hand lag when turning.")
        .defaultValue(false).build());

    public final Setting<Boolean> noTridentAnim = sgMisc.add(new BoolSetting.Builder()
        .name("no-trident-anim").description("Removes the trident charge animation.")
        .defaultValue(false).build());

    public final Setting<Boolean> viewModelFov = sgMisc.add(new BoolSetting.Builder()
        .name("view-model-fov").description("Uses a separate FOV for held items.")
        .defaultValue(false).build());

    public final Setting<Integer> fovAmount = sgMisc.add(new IntSetting.Builder()
        .name("fov-amount").description("FOV used for held items.")
        .defaultValue(90).range(70, 180).sliderRange(70, 180).visible(viewModelFov::get).build());

    public final Setting<Boolean> instantSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("instant-swap").description("Skips the equip animation when switching items.")
        .defaultValue(false).build());

    private final Setting<Boolean> swingOverride = sgSwing.add(new BoolSetting.Builder()
        .name("swing-override").description("Overrides swing progress per hand.")
        .defaultValue(false).build());

    private final Setting<Boolean> staticSwing = sgSwing.add(new BoolSetting.Builder()
        .name("static").description("Freezes the swing animation at the minimum progress.")
        .defaultValue(true).visible(swingOverride::get).build());

    private final HandSettings main = new HandSettings(sgMain, sgSwing, "main");
    private final HandSettings off = new HandSettings(sgOff, sgSwing, "off");

    public ViewModel() {
        super(AddonTemplate.CATEGORY, "view-model", "Per-hand translate, scale and rotate for held items, plus swing and equip control.");
    }

    public void apply(PoseStack pose, AbstractClientPlayer player, InteractionHand hand) {
        boolean isMain = hand == InteractionHand.MAIN_HAND;
        HandSettings h = isMain ? main : off;
        if (!h.enabled.get()) return;

        HumanoidArm arm = isMain ? player.getMainArm() : player.getMainArm().getOpposite();
        float ax = (arm == HumanoidArm.RIGHT ? 1 : -1) * 0.56f, ay = -0.52f, az = -0.72f;

        pose.translate(h.x.get(), h.y.get(), h.z.get());
        pose.translate(ax, ay, az);
        pose.mulPose(Axis.XP.rotationDegrees(h.rx.get().floatValue()));
        pose.mulPose(Axis.YP.rotationDegrees(h.ry.get().floatValue()));
        pose.mulPose(Axis.ZP.rotationDegrees(h.rz.get().floatValue()));
        pose.scale(h.sx.get().floatValue(), h.sy.get().floatValue(), h.sz.get().floatValue());
        pose.translate(-ax, -ay, -az);
    }

    public float swing(InteractionHand hand, float original) {
        if (!swingOverride.get()) return original;

        HandSettings h = hand == InteractionHand.MAIN_HAND ? main : off;
        if (!h.swing.get()) return original;

        float base = staticSwing.get() ? 0f : original;
        return Math.max(base, h.swingAmount.get().floatValue());
    }

    private class HandSettings {
        final Setting<Boolean> enabled, swing;
        final Setting<Double> x, y, z, sx, sy, sz, rx, ry, rz, swingAmount;

        HandSettings(SettingGroup g, SettingGroup sg, String p) {
            enabled = g.add(new BoolSetting.Builder().name(p + "-enabled").description("Applies the transform to this hand.").defaultValue(true).build());

            x = num(g, p + "-x", 0, -2, 2);
            y = num(g, p + "-y", 0, -2, 2);
            z = num(g, p + "-z", 0, -2, 2);
            sx = num(g, p + "-scale-x", 1, 0, 3);
            sy = num(g, p + "-scale-y", 1, 0, 3);
            sz = num(g, p + "-scale-z", 1, 0, 3);
            rx = num(g, p + "-rotate-x", 0, 0, 360);
            ry = num(g, p + "-rotate-y", 0, 0, 360);
            rz = num(g, p + "-rotate-z", 0, 0, 360);

            swing = sg.add(new BoolSetting.Builder().name(p + "-swing").description("Overrides this hand's swing progress.")
                .defaultValue(false).visible(swingOverride::get).build());

            swingAmount = sg.add(new DoubleSetting.Builder().name(p + "-swing-amount").description("Minimum swing progress.")
                .defaultValue(1.0).range(0, 1).sliderRange(0, 1)
                .visible(() -> swingOverride.get() && swing.get()).build());
        }

        private Setting<Double> num(SettingGroup g, String name, double def, double min, double max) {
            return g.add(new DoubleSetting.Builder().name(name).defaultValue(def)
                .range(min, max).sliderRange(min, max).visible(enabled::get).build());
        }
    }
}
