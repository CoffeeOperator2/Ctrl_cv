package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura; // 🌟 Path fix for standard Meteor Aura
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class SwordGap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> preferEGap = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-egaps")
        .description("Use Enchanted Golden Apples before regular ones.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> bridgeSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Hotbar slot (1-9) a gapple from your inventory gets pulled into.")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("After you let go, put the item that was in that hotbar slot back where it was.")
        .defaultValue(true)
        .build()
    );

    private boolean swapped;
    private int swordSlot = -1;
    private int pulledFrom = -1;
    private boolean auraWasActive = false; // Tracks if we need to turn aura back on

    public SwordGap() {
        super(Categories.Combat, "sword-gap", "Hold right click with a sword to switch to a gapple, release to switch back.");
    }

    @Override
    public void onDeactivate() {
        if (swapped) restore();
        reset();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // 🌟 FIXED: Swapped 'screen' to Mojang's native 'screen' field mapping
        boolean useHeld = mc.options.keyUse.isDown() && mc.gui.screen() == null;
        if (swapped) {
            if (!useHeld) {
                restore();
                reset();
            }
            return;
        }


        if (!useHeld) return;
        if (!mc.player.getMainHandItem().is(ItemTags.SWORDS)) return;
        if (isGap(mc.player.getOffhandItem())) return;

        FindItemResult apple = findApple();
        if (!apple.found()) return;

        // 🌟 FIXED: Uses your project's verified hotbar index getter mapping path
        swordSlot = mc.player.getInventory().getSelectedSlot();

        // 🛑 PAUSE KILLAURA: Turns off Killaura instantly before moving items to prevent packet hijacking
        KillAura aura = Modules.get().get(KillAura.class);
        if (aura != null && aura.isActive()) {
            aura.toggle();
            auraWasActive = true;
        }

        if (apple.slot() <= 8) {
            pulledFrom = -1;
            mc.player.getInventory().setSelectedSlot(apple.slot());
        } else {
            int bridge = bridgeSlot.get() - 1;
            pulledFrom = apple.slot();
            InvUtils.move().from(apple.slot()).to(bridge);
            mc.player.getInventory().setSelectedSlot(bridge);
        }

        swapped = true;
    }

    private FindItemResult findApple() {
        Item first = preferEGap.get() ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
        Item second = preferEGap.get() ? Items.GOLDEN_APPLE : Items.ENCHANTED_GOLDEN_APPLE;

        FindItemResult result = InvUtils.find(stack -> stack.getItem() == first, 0, 35);
        if (!result.found()) result = InvUtils.find(stack -> stack.getItem() == second, 0, 35);
        return result;
    }

    private boolean isGap(ItemStack stack) {
        return stack.getItem() == Items.GOLDEN_APPLE || stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE;
    }

    private void restore() {
        if (mc.player == null) return;

        // 🌟 FIXED: Restores slot using your project's verified setter mapping path
        if (swordSlot != -1) mc.player.getInventory().setSelectedSlot(swordSlot);

        if (swapBack.get() && pulledFrom != -1) {
            int bridge = bridgeSlot.get() - 1;
            if (!mc.player.getInventory().getItem(bridge).isEmpty()) {
                InvUtils.move().from(bridge).to(pulledFrom);
            }
        }

        // 🚀 RESUME KILLAURA: Safely reactivates your aura once you release your right-click button
        if (auraWasActive) {
            KillAura aura = Modules.get().get(KillAura.class);
            if (aura != null && !aura.isActive()) aura.toggle();
        }
    }

    private void reset() {
        swapped = false;
        swordSlot = -1;
        pulledFrom = -1;
        auraWasActive = false;
    }
}
