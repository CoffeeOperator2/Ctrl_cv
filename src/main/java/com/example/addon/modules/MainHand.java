/*
 * Based on the structure of Meteor Client's Offhand module.
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development. Licensed under GPL-3.0.
 */

package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

import static meteordevelopment.orbit.EventPriority.HIGHEST;

public class MainHand extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTotem = settings.createGroup("Totem");

    // General

    private final Setting<Item> preferredItem = sgGeneral.add(new EnumSetting.Builder<Item>()
        .name("item")
        .description("Item to always hold in your main hand. None = only switch when in danger.")
        .defaultValue(Item.None)
        .build()
    );

    private final Setting<Boolean> hotbarOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("hotbar-only")
        .description("Only look for the item in your hotbar. Off = also pull it from your inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait between switches.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    // Totem

    private final Setting<Double> minHealth = sgTotem.add(new DoubleSetting.Builder()
        .name("min-health")
        .description("Hold a totem when your health is at or below this.")
        .defaultValue(10)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Boolean> elytra = sgTotem.add(new BoolSetting.Builder()
        .name("elytra")
        .description("Always hold a totem while flying with an elytra.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> falling = sgTotem.add(new BoolSetting.Builder()
        .name("falling")
        .description("Hold a totem if fall damage could kill you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> explosion = sgTotem.add(new BoolSetting.Builder()
        .name("explosion")
        .description("Hold a totem if explosion damage could kill you.")
        .defaultValue(true)
        .build()
    );

    private int ticks;
    private int savedSlot = -1;     // the slot you were on before the totem switch
    private boolean sentMessage;

    public MainHand() {
        super(Categories.Combat, "main-hand", "Holds a totem (or another item) in your main hand when needed.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        savedSlot = -1;
        sentMessage = false;
    }

    @Override
    public void onDeactivate() {
        restoreSlot();
    }

    @EventHandler(priority = HIGHEST + 999)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (ticks < delayTicks.get()) {
            ticks++;
            return;
        }
        ticks = 0;

        // 1) Are we in danger?
        boolean low = mc.player.getHealth() + mc.player.getAbsorptionAmount()
            - PlayerUtils.possibleHealthReductions(explosion.get(), falling.get()) <= minHealth.get();
        boolean ely = elytra.get()
            && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA
            && mc.player.isFallFlying();
        boolean danger = low || ely;

        // 2) Danger is over: go back to the slot we came from.
        if (!danger && savedSlot != -1) {
            restoreSlot();
            return;
        }

        // 3) Decide what we want in the main hand.
        net.minecraft.world.item.Item wanted = null;
        if (danger) wanted = Items.TOTEM_OF_UNDYING;
        else if (preferredItem.get() != Item.None) wanted = preferredItem.get().item;

        if (wanted == null) return;                                       // nothing to do
        if (mc.player.getMainHandItem().getItem() == wanted) return;      // already holding it

        // 4) Find it.
        final net.minecraft.world.item.Item target = wanted;
        FindItemResult found = InvUtils.find(stack -> stack.getItem() == target, 0, hotbarOnly.get() ? 8 : 35);

        if (!found.found()) {
            if (!sentMessage) {
                warning("Item not found.");
                sentMessage = true;
            }
            return;
        }
        sentMessage = false;

        // Remember where we were, once, before the first danger switch.
        if (danger && savedSlot == -1) savedSlot = mc.player.getInventory().getSelectedSlot();

        // 5) Get it into the main hand.
        if (found.slot() <= 8) {
            // It's in the hotbar: just select that slot.
            mc.player.getInventory().setSelectedSlot(found.slot());
        } else {
            // It's in the inventory: move it into the slot we're holding.
            InvUtils.move().from(found.slot()).to(mc.player.getInventory().getSelectedSlot());
        }
    }

    private void restoreSlot() {
        if (savedSlot != -1 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(savedSlot);
        }
        savedSlot = -1;
    }

    @Override
    public String getInfoString() {
        return preferredItem.get().name();
    }

    public enum Item {
        None(null),
        EGap(Items.ENCHANTED_GOLDEN_APPLE),
        Gap(Items.GOLDEN_APPLE),
        Crystal(Items.END_CRYSTAL),
        Totem(Items.TOTEM_OF_UNDYING),
        Potion(Items.POTION);

        final net.minecraft.world.item.Item item;

        Item(net.minecraft.world.item.Item item) {
            this.item = item;
        }
    }
}
