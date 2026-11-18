//dosen't work yet :(

package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.HashMap;
import java.util.Map;

public class GlobalColor extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<SettingColor> color = sg.add(new ColorSetting.Builder()
        .name("color").description("Color applied to every module's color settings.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .onChanged(c -> apply()).build());

    // Original colors, so turning the module off restores them.
    private final Map<ColorSetting, SettingColor> backup = new HashMap<>();

    public GlobalColor() {
        super(AddonTemplate.CATEGORY, "global-color", "Sets every module's color settings at once.");
    }

    @Override
    public void onActivate() {
        backup.clear();
        for (Module module : Modules.get().getAll()) {
            if (module == this) continue;
            for (SettingGroup group : module.settings) {
                for (Setting<?> setting : group) {
                    if (setting instanceof ColorSetting cs) backup.put(cs, new SettingColor(cs.get()));
                }
            }
        }
        apply();
    }

    @Override
    public void onDeactivate() {
        backup.forEach(Setting::set);
        backup.clear();
    }

    private void apply() {
        if (!isActive()) return;
        for (ColorSetting cs : backup.keySet()) cs.set(new SettingColor(color.get()));
    }
}
