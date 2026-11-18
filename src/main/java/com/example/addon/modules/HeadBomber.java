package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class HeadBomber extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Radius to scan for players in holes.")
        .defaultValue(6.0)
        .min(1.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Maximum reach distance for placing and mining.")
        .defaultValue(5.0)
        .min(1.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Double> crystalThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("crystal-threshold")
        .description("Mining progress ratio at which the crystal is placed on top of the head block.")
        .defaultValue(0.75)
        .min(0.4)
        .max(0.95)
        .sliderRange(0.4, 0.95)
        .build()
    );

    private final Setting<Double> breakThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("break-threshold")
        .description("Mining progress ratio at which STOP_DESTROY_BLOCK is sent.")
        .defaultValue(0.85)
        .min(0.6)
        .max(1.0)
        .sliderRange(0.6, 1.0)
        .build()
    );

    private final Setting<Integer> swapSlot = sgGeneral.add(new IntSetting.Builder()
        .name("swap-slot")
        .description("Hotbar slot (1-9) used for silent inventory swapping.")
        .defaultValue(9)
        .min(1)
        .max(9)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates toward blocks and crystals during actions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-info")
        .description("Sends status alerts in chat.")
        .defaultValue(true)
        .build()
    );

    // Render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the head block being bombed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Side color of rendered block.")
        .defaultValue(new SettingColor(220, 20, 60, 60))
        .visible(() -> render.get() && shapeMode.get().sides())
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color of rendered block.")
        .defaultValue(new SettingColor(220, 20, 60, 255))
        .visible(() -> render.get() && shapeMode.get().lines())
        .build()
    );

    private Player target;
    private BlockPos headPos;
    private boolean startedMining;
    private boolean crystalPlaced;
    private boolean stopSent;
    private int mineStartTick;
    private int cooldown;

    public HeadBomber() {
        super(AddonTemplate.CATEGORY, "head-bomber", "Places obsidian above a target's head, mines it, places a crystal right before break, detonates, and repeats.");
    }

    @Override
    public void onActivate() {
        resetCycle();
        target = null;
    }

    @Override
    public void onDeactivate() {
        resetCycle();
        target = null;
    }

    private void resetCycle() {
        headPos = null;
        startedMining = false;
        crystalPlaced = false;
        stopSent = false;
        mineStartTick = 0;
        cooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (target == null || target.isDeadOrDying() || target.distanceTo(mc.player) > targetRange.get()) {
            target = TargetUtils.getPlayerTarget(targetRange.get(), SortPriority.LowestDistance);
            if (target == null) return;
            resetCycle();
        }

        BlockPos currentHead = target.blockPosition().above(2);
        if (headPos == null || !headPos.equals(currentHead)) {
            resetCycle();
            headPos = currentHead;
        }

        if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(headPos)) > placeRange.get()) return;

        BlockState headState = mc.level.getBlockState(headPos);
        EndCrystal existingCrystal = findCrystalAt(headPos.above());

        // Step 5: Once the head block has broken to air and a crystal is sitting there, detonate immediately and restart
        if (headState.isAir() && existingCrystal != null) {
            detonateCrystal(existingCrystal);
            resetCycle();
            headPos = currentHead;
            cooldown = 1;
            return;
        }

        // Step 1: If no obsidian is above their head yet (and no lingering crystal), place obsidian
        if (headState.canBeReplaced()) {
            if (existingCrystal != null) {
                detonateCrystal(existingCrystal);
                cooldown = 1;
                return;
            }

            int obbySlot = ensureInHotbar(Items.OBSIDIAN);
            if (obbySlot == -1) {
                if (chatInfo.get()) warning("No obsidian in inventory.");
                return;
            }

            if (BlockUtils.place(headPos, new FindItemResult(obbySlot, 1), rotate.get(), 0, true)) {
                startedMining = false;
                crystalPlaced = false;
                stopSent = false;
                cooldown = 1;
            }
            return;
        }

        // Cannot mine bedrock
        if (headState.is(Blocks.BEDROCK)) return;

        // Step 2: Start packet-mining the block above their head
        if (!startedMining) {
            startPacketMine(headPos);
            return;
        }

        double progress = getMineProgress(headState);

        // Step 3: Right before the block breaks, place an end crystal on top of it
        if (!crystalPlaced && progress >= crystalThreshold.get() && existingCrystal == null) {
            placeCrystalOn(headPos);
            crystalPlaced = true;
            return;
        }

        // Step 4: Send STOP_DESTROY_BLOCK with best pickaxe once breakThreshold is reached
        if (progress >= breakThreshold.get() && (!stopSent || mc.player.tickCount % 2 == 0)) {
            finishPacketMine(headPos, headState);
        }
    }


    private void startPacketMine(BlockPos pos) {
        Runnable startAction = () -> {
            mc.player.swing(InteractionHand.MAIN_HAND);
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP
            ));
            startedMining = true;
            mineStartTick = mc.player.tickCount;
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 50, startAction);
        } else {
            startAction.run();
        }
    }

    private void finishPacketMine(BlockPos pos, BlockState state) {
        FindItemResult pickaxe = InvUtils.findFastestTool(state);
        int hotbarSlot = -1;
        if (pickaxe.found()) {
            hotbarSlot = pickaxe.isHotbar() ? pickaxe.slot() : ensureInHotbar(mc.player.getInventory().getItem(pickaxe.slot()).getItem());
        }

        int prev = mc.player.getInventory().getSelectedSlot();
        if (hotbarSlot != -1) mc.player.connection.send(new ServerboundSetCarriedItemPacket(hotbarSlot));

        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP
        ));

        if (hotbarSlot != -1) mc.player.connection.send(new ServerboundSetCarriedItemPacket(prev));
        stopSent = true;
    }

    private void placeCrystalOn(BlockPos supportBlock) {
        int crystalSlot = ensureInHotbar(Items.END_CRYSTAL);
        if (crystalSlot == -1) {
            if (chatInfo.get()) warning("No end crystals in inventory.");
            return;
        }

        Runnable placeAction = () -> {
            int prev = mc.player.getInventory().getSelectedSlot();
            BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(supportBlock).add(0, 0.5, 0),
                Direction.UP,
                supportBlock,
                false
            );
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(crystalSlot));
            mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
            mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(prev));
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(supportBlock), Rotations.getPitch(supportBlock), 50, placeAction);
        } else {
            placeAction.run();
        }
    }

    private void detonateCrystal(EndCrystal crystal) {
        Runnable attackAction = () -> {
            mc.gameMode.attack(mc.player, crystal);
            mc.player.swing(InteractionHand.MAIN_HAND);
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(crystal), Rotations.getPitch(crystal), 50, attackAction);
        } else {
            attackAction.run();
        }
    }

    private double getMineProgress(BlockState state) {
        FindItemResult pickaxe = InvUtils.findFastestTool(state);
        int slot = pickaxe.found() ? pickaxe.slot() : mc.player.getInventory().getSelectedSlot();
        double delta = BlockUtils.getBreakDelta(slot, state);
        int elapsed = (mc.player.tickCount - mineStartTick) + 1;
        return delta * elapsed;
    }

    private int ensureInHotbar(Item item) {
        FindItemResult hotbar = InvUtils.findInHotbar(item);
        if (hotbar.found()) return hotbar.slot();

        FindItemResult inv = InvUtils.find(item);
        if (!inv.found()) return -1;

        int dest = swapSlot.get() - 1;
        InvUtils.move().from(inv.slot()).toHotbar(dest);
        return dest;
    }

    private EndCrystal findCrystalAt(BlockPos pos) {
        List<EndCrystal> list = mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(pos));
        return list.isEmpty() ? null : list.get(0);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || headPos == null) return;
        event.renderer.box(headPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    @Override
    public String getInfoString() {
        return EntityUtils.getName(target);
    }
}


