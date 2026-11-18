package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;

public class SydneyAspectRatio extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Double> ratio = sgGeneral.add(new DoubleSetting.Builder()
        .name("ratio")
        .description("The custom aspect ratio scaling target multiplier.")
        .defaultValue(1.78)
        .min(0.1)
        .sliderMax(5.0)
        .build()
    );

    public SydneyAspectRatio() {
        super(AddonTemplate.CATEGORY, "aspect-ratio", "Modifies the game's global visual rendering aspect ratio layout matrix.");
    }

    // 🌟 THE OVERCLOCK: Instead of an empty tick pass, we override the standard window aspect ratio tracker
    public float getCustomAspectRatio() {
        if (!isActive() || mc.getWindow() == null) {
            return mc.getWindow() != null ? (float) mc.getWindow().getWidth() / (float) mc.getWindow().getHeight() : 1.78f;
        }
        return ratio.get().floatValue();
    }

    @Override
    public String getInfoString() {
        return String.format("%.2f", ratio.get());
    }
}
