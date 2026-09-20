package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class FeetPlace extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // --- Restored Settings From Mio Combat Core Matrix ---
    public final Setting<Boolean> center = sgGeneral.add(new BoolSetting.Builder().name("center").description("Snaps you to the block core center axis on toggle.").defaultValue(true).build());
    public final Setting<PlacementMode> placeMode = sgGeneral.add(new EnumSetting.Builder<PlacementMode>().name("place-mode").description("Switches between raw server injection packets or vanilla interaction layers.").defaultValue(PlacementMode.Packet).build());
    public final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder().name("delay-ticks").description("Game ticks to wait between block placement sweeps.").defaultValue(1).min(0).sliderMax(10).build());
    public final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder().name("blocks-per-tick").description("Maximum placements allowed inside a single frame pass.").defaultValue(4).min(1).sliderMax(8).build());
    public final Setting<Boolean> disableOnY = sgGeneral.add(new BoolSetting.Builder().name("disable-on-y").description("Automatically toggles off if your vertical altitude changes.").defaultValue(true).build());
    public final Setting<Boolean> flatten = sgGeneral.add(new BoolSetting.Builder().name("flatten").description("Forces lower floor blocks to deploy before drawing the walls.").defaultValue(false).build());
    public final Setting<Boolean> onlyObby = sgGeneral.add(new BoolSetting.Builder().name("only-obby").description("Restricts usable material profiles strictly to Obsidian stock.").defaultValue(true).build());

    private int timer = 0;
    private double startY = 0;

    public enum PlacementMode { Packet, Client }

    public FeetPlace() {
        super(AddonTemplate.CATEGORY, "feet-place", "Advanced surround protection grid mapping foot rings and bedrock sealing layouts.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        if (mc.player == null || mc.level == null) return;

        // Locks your starting height position frame instantly for the disableOnY validation checks
        startY = mc.player.getY();

        if (center.get() && mc.player.onGround()) {
            double centerX = Math.floor(mc.player.getX()) + 0.5;
            double centerZ = Math.floor(mc.player.getZ()) + 0.5;
            mc.player.setPos(centerX, mc.player.getY(), centerZ);
        }
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        // --- STAGE 1: DYNAMIC ALTITUDE SAFETIES VALVE ---
        if (disableOnY.get() && Math.floor(mc.player.getY()) != Math.floor(startY)) {
            toggle();
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        FindItemResult block = onlyObby.get() ? InvUtils.findInHotbar(Items.OBSIDIAN) : InvUtils.findInHotbar(Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.ENDER_CHEST);
        if (!block.found()) return;

        BlockPos playerPos = mc.player.blockPosition();
        List<BlockPos> targets = new ArrayList<>();

        if (flatten.get()) targets.add(playerPos.below());
        targets.add(playerPos.north());
        targets.add(playerPos.south());
        targets.add(playerPos.east());
        targets.add(playerPos.west());
        if (!flatten.get()) targets.add(playerPos.below());

        int placed = 0;
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        for (BlockPos pos : targets) {
            if (placed >= blocksPerTick.get()) break;

            if (mc.level.getBlockState(pos).canBeReplaced()) {
                // --- STAGE 2: EXECUTE CHOSEN INTERACTION ROUTINE ---
                if (placeMode.get() == PlacementMode.Packet) {
                    // Silent network packet swap tracking injection bypass
                    InvUtils.swap(block.slot(), false);

                    BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                    mc.getConnection().send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hitResult, 0));
                    mc.player.swing(InteractionHand.MAIN_HAND);

                    InvUtils.swap(previousSlot, false);
                    placed++;
                } else {
                    // Standard Client-Side Placement path relying on Meteor's native block engine wrappers
                    if (BlockUtils.place(pos, block, false, 50, true, false)) {
                        placed++;
                    }
                }
            }
        }

        if (placed > 0) {
            timer = delay.get();
        }
    }
}

