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
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class HolePush extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    public enum Mode {
        Piston,
        Shulker
    }

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Method used to push the player out of their hole.")
        .defaultValue(Mode.Piston)
        .build()
    );

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
        .description("Maximum reach distance for placing blocks.")
        .defaultValue(5.0)
        .min(1.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between push steps.")
        .defaultValue(1)
        .min(0)
        .sliderMax(10)
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

    private final Setting<Boolean> preferSticky = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-sticky")
        .description("Uses sticky pistons before regular pistons.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Piston)
        .build()
    );

    private final Setting<Boolean> cycleRedstone = sgGeneral.add(new BoolSetting.Builder()
        .name("cycle-redstone")
        .description("Instantly mines the redstone block and places another to continuously push the player.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Piston)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates toward blocks when placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Disables once the target has been pushed out of their hole.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-info")
        .description("Sends status messages in chat.")
        .defaultValue(true)
        .build()
    );

    // Render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the surround push position.")
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
        .defaultValue(new SettingColor(0, 170, 255, 60))
        .visible(() -> render.get() && shapeMode.get().sides())
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color of rendered block.")
        .defaultValue(new SettingColor(0, 170, 255, 255))
        .visible(() -> render.get() && shapeMode.get().lines())
        .build()
    );

    private Player target;
    private BlockPos lockedHolePos;
    private Direction pushDir;
    private BlockPos surroundPos;
    private BlockPos pushDevicePos;
    private BlockPos redstonePos;
    private int cooldown;


    public HolePush() {
        super(AddonTemplate.CATEGORY, "hole-push", "Places a piston or shulker on a target's surround and cycles redstone to push them out.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        target = null;
        lockedHolePos = null;
        pushDir = null;
        surroundPos = null;
        pushDevicePos = null;
        redstonePos = null;
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
            lockedHolePos = target.blockPosition();
        }

        // Check if target has already been pushed out of their original hole
        if (lockedHolePos != null && !target.blockPosition().equals(lockedHolePos)) {
            if (chatInfo.get()) info("Pushed " + EntityUtils.getName(target) + " out of hole.");
            if (autoDisable.get()) {
                toggle();
                return;
            }
            lockedHolePos = target.blockPosition();
        }

        // Continuously evaluate all 4 sides around the target to pick the best available push angle
        if (!findBestSideAroundTarget(target)) {
            return;
        }

        if (mode.get() == Mode.Shulker) {
            executeShulkerOnSurround();
            cooldown = delay.get() + 2;
        } else {
            executePistonCycle();
        }
    }

    /**
     * Evaluates all 4 horizontal sides around the target:
     * Prioritizes a side that already has a valid piston placed, otherwise picks the closest open surround side.
     */
    private boolean findBestSideAroundTarget(Player player) {
        BlockPos feet = player.blockPosition();
        double bestScore = Double.MAX_VALUE;
        boolean found = false;

        for (Direction dirToPlayer : Direction.Plane.HORIZONTAL) {
            Direction outward = dirToPlayer.getOpposite();
            BlockPos sPos = feet.relative(outward);

            // Must have a solid surround block to sit on
            if (mc.level.getBlockState(sPos).canBeReplaced()) continue;

            // Exit path behind the player's head must be clear so they can be pushed out
            BlockPos exitHead = feet.above().relative(dirToPlayer);
            if (!mc.level.getBlockState(exitHead).canBeReplaced()) continue;

            BlockPos devicePos = sPos.above();
            BlockState deviceState = mc.level.getBlockState(devicePos);
            boolean hasPiston = mode.get() == Mode.Piston && isValidPiston(deviceState, dirToPlayer);
            if (!hasPiston && !deviceState.canBeReplaced()) continue;

            if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(devicePos)) > placeRange.get()) continue;

            BlockPos rPos = null;
            if (mode.get() == Mode.Piston) {
                rPos = findRedstonePos(devicePos, dirToPlayer);
                if (rPos == null) continue;
            }

            // Give existing valid pistons a huge score bonus so we reuse them instead of placing new ones
            double score = mc.player.distanceToSqr(Vec3.atCenterOf(devicePos)) - (hasPiston ? 100.0 : 0.0);
            if (score < bestScore) {
                bestScore = score;
                pushDir = dirToPlayer;
                surroundPos = sPos;
                pushDevicePos = devicePos;
                redstonePos = rPos;
                found = true;
            }
        }

        return found;
    }

    private BlockPos findRedstonePos(BlockPos pPos, Direction facing) {
        for (Direction side : Direction.values()) {
            if (side == facing) continue;
            BlockPos candidate = pPos.relative(side);
            BlockState state = mc.level.getBlockState(candidate);
            if (state.is(Blocks.REDSTONE_BLOCK) || state.canBeReplaced()) {
                if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(candidate)) <= placeRange.get()) {
                    return candidate;
                }
            }
        }
        return null;
    }


    private void executePistonCycle() {
        BlockState pistonState = mc.level.getBlockState(pushDevicePos);

        // 1. Always verify the piston is still there and facing the right direction; if missing, place one
        if (!isValidPiston(pistonState, pushDir)) {
            if (pistonState.is(Blocks.PISTON) || pistonState.is(Blocks.STICKY_PISTON)) {
                packetBreak(pushDevicePos);
                cooldown = 1;
                return;
            }

            Item primary = preferSticky.get() ? Items.STICKY_PISTON : Items.PISTON;
            Item secondary = preferSticky.get() ? Items.PISTON : Items.STICKY_PISTON;
            int slot = ensureInHotbar(primary);
            if (slot == -1) slot = ensureInHotbar(secondary);
            if (slot == -1) {
                if (chatInfo.get()) warning("No piston in inventory.");
                return;
            }

            final int pistonSlot = slot;
            Runnable placePiston = () -> {
                if (BlockUtils.place(pushDevicePos, new FindItemResult(pistonSlot, 1), false, 0, true)) {
                    cooldown = delay.get();
                }
            };

            if (rotate.get()) {
                Rotations.rotate(pushDir.getOpposite().toYRot(), 0, 50, placePiston);
            } else {
                placePiston.run();
            }
            return;
        }

        // 2. Piston is confirmed present: if redstone is already powering it and cycle-redstone is enabled,
        //    instantly mine the redstone block so it retracts and re-fires on the next cycle
        if (mc.level.getBlockState(redstonePos).is(Blocks.REDSTONE_BLOCK)) {
            if (cycleRedstone.get()) {
                packetBreak(redstonePos);
                cooldown = delay.get();
            }
            return;
        }

        // 3. Place redstone block to fire the piston
        int redstoneSlot = ensureInHotbar(Items.REDSTONE_BLOCK);
        if (redstoneSlot == -1) {
            if (chatInfo.get()) warning("No redstone block in inventory.");
            return;
        }

        if (BlockUtils.place(redstonePos, new FindItemResult(redstoneSlot, 1), rotate.get(), 0, true)) {
            cooldown = delay.get() + 1;
        }
    }

    private void executeShulkerOnSurround() {
        int foundSlot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock) {
                foundSlot = i;
                break;
            }
        }
        if (foundSlot == -1) {
            if (chatInfo.get()) warning("No shulker box in inventory.");
            return;
        }

        int hotbarSlot = foundSlot < 9 ? foundSlot : swapSlot.get() - 1;
        if (foundSlot >= 9) {
            InvUtils.move().from(foundSlot).toHotbar(hotbarSlot);
        }

        BlockPos anchor = pushDevicePos.relative(pushDir.getOpposite());
        Direction face = pushDir;
        if (mc.level.getBlockState(anchor).canBeReplaced()) {
            anchor = surroundPos;
            face = Direction.UP;
        }

        int prev = mc.player.getInventory().getSelectedSlot();
        BlockHitResult placeHit = new BlockHitResult(
            Vec3.atCenterOf(anchor).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5)),
            face,
            anchor,
            false
        );
        BlockHitResult openHit = new BlockHitResult(Vec3.atCenterOf(pushDevicePos), face, pushDevicePos, false);

        mc.player.connection.send(new ServerboundSetCarriedItemPacket(hotbarSlot));
        mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, placeHit, 0));
        mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, openHit, 1));
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(prev));
    }

    private void packetBreak(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        FindItemResult tool = InvUtils.findFastestTool(state);
        int hotbarSlot = -1;
        if (tool.found()) {
            hotbarSlot = tool.isHotbar() ? tool.slot() : ensureInHotbar(mc.player.getInventory().getItem(tool.slot()).getItem());
        }

        int prev = mc.player.getInventory().getSelectedSlot();
        if (hotbarSlot != -1) mc.player.connection.send(new ServerboundSetCarriedItemPacket(hotbarSlot));

        mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
        mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP));

        if (hotbarSlot != -1) mc.player.connection.send(new ServerboundSetCarriedItemPacket(prev));
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

    private boolean isValidPiston(BlockState state, Direction facing) {
        if (!state.is(Blocks.PISTON) && !state.is(Blocks.STICKY_PISTON)) return false;
        return state.getValue(PistonBaseBlock.FACING) == facing;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || pushDevicePos == null) return;
        event.renderer.box(pushDevicePos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    @Override
    public String getInfoString() {
        return EntityUtils.getName(target);
    }
}

