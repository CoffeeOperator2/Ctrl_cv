package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SpeedMine extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    public final Setting<SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<SwitchMode>().name("switch-mode").defaultValue(SwitchMode.Silent).build());
    public final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder().name("range").defaultValue(6.0).min(0.0).max(8.0).build());
    public final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder().name("speed").defaultValue(1.0).min(0.7).max(1.0).build());
    public final Setting<Boolean> auto = sgGeneral.add(new BoolSetting.Builder().name("auto").defaultValue(false).build());
    public final Setting<Boolean> doubleMine = sgGeneral.add(new BoolSetting.Builder().name("double-mine").defaultValue(false).build());
    public final Setting<Boolean> instant = sgGeneral.add(new BoolSetting.Builder().name("instant").defaultValue(true).build());
    public final Setting<Boolean> grim = sgGeneral.add(new BoolSetting.Builder().name("grim-bypass").defaultValue(false).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").defaultValue(ShapeMode.Both).build());

    public Action primary = null;
    public Action secondary = null;

    private BlockPos lastBrokenPos = null;
    private Direction lastDirection = Direction.UP;

    public enum SwitchMode {Silent, Normal, None}

    public SpeedMine() {
        super(AddonTemplate.CATEGORY, "speed-mine", "High-performance multi-targeted packet mining engine ported from Sydney source.");
    }

    @Override
    public void onActivate() {
        primary = null;
        secondary = null;
        lastBrokenPos = null;
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.getConnection() != null) {
            if (primary != null) primary.cancel();
            if (secondary != null) secondary.cancel();
        }
        primary = null;
        secondary = null;
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        if (doubleMine.get() && secondary != null) {
            if (secondary.process()) secondary = null;
        }
        if (primary != null) {
            if (primary.process()) primary = null;
        }

        if (instant.get() && lastBrokenPos != null && !mc.level.getBlockState(lastBrokenPos).isAir()) {
            int toolSlot = getTool(lastBrokenPos);
            int previousSlot = mc.player.getInventory().getSelectedSlot();

            if (toolSlot != -1 && switchMode.get() == SwitchMode.Silent) InvUtils.swap(toolSlot, false);

            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, lastBrokenPos, lastDirection));
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, lastBrokenPos, lastDirection));
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, lastBrokenPos, lastDirection));

            if (toolSlot != -1 && switchMode.get() == SwitchMode.Silent) InvUtils.swap(previousSlot, false);
        }

        if (!auto.get()) return;

        Player optimalTarget = mc.level.players().stream()
            .filter(p -> p != mc.player && p.isAlive() && !Friends.get().isFriend(p))
            .filter(p -> mc.player.distanceToSqr(p) <= (range.get() + 2.0) * (range.get() + 2.0))
            .min(Comparator.comparingDouble(p -> mc.player.distanceToSqr(p)))
            .orElse(null);

        if (optimalTarget == null) return;

        BlockPos targetFeet = optimalTarget.blockPosition();
        List<BlockPos> targetBlocks = new ArrayList<>();
        targetPositionsMapped(targetFeet, targetBlocks);

        for (BlockPos pos : targetBlocks) {
            if (mc.level.getBlockState(pos).isAir() || mc.level.getBlockState(pos).is(Blocks.BEDROCK)) continue;
            if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > range.get() * range.get()) continue;

            handle(pos);
            if (!doubleMine.get()) break;
        }
    }

    private void targetPositionsMapped(BlockPos center, List<BlockPos> list) {
        list.add(center.north());
        list.add(center.south());
        list.add(center.east());
        list.add(center.west());
        list.add(center.below());
    }

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (mc.player == null || mc.level == null || mc.player.isCreative()) return;
        if (handle(event.blockPos)) {
            event.setCancelled(true);
        }
    }


    public boolean handle(BlockPos position) {
        if (mc.player == null || mc.level == null) return false;
        if (mc.level.getBlockState(position).getDestroySpeed(mc.level, position) == -1) return false;

        if ((primary != null && primary.position.equals(position)) || (secondary != null && secondary.position.equals(position)))
            return true;

        if (doubleMine.get()) {
            if (secondary != null) {
                primary = new Action(position);
            } else {
                if (primary != null) {
                    secondary = primary;
                    primary = new Action(position);
                } else {
                    primary = new Action(position);
                }
            }
        } else {
            if (primary != null) primary.cancel();
            primary = new Action(position);
        }

        return true;
    }

    public int getTool(BlockPos pos) {
        int index = -1;
        float currentFastest = 1.0f;
        if (mc.level == null) return -1;
        BlockState state = mc.level.getBlockState(pos);
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                float destroySpeed = stack.getDestroySpeed(state);
                if (destroySpeed > currentFastest) {
                    currentFastest = destroySpeed;
                    index = i;
                }
            }
        }
        return index;
    }

    public class Action {
        public final BlockPos position;
        public BlockState blockState;
        public float progress;
        public final Direction direction;

        public Action(BlockPos position) {
            this.position = position;
            this.blockState = mc.level.getBlockState(position);
            this.direction = Direction.UP;
            this.progress = 0.0f;
            start();
        }

        public void start() {
            if (mc.getConnection() == null) return;
            if (doubleMine.get()) {
                mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction));
                mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction));
                mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction));
            } else {
                mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction));
                mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position, direction));
            }
        }

        public boolean process() {
            if (mc.level == null || mc.player == null || mc.getConnection() == null) return true;
            if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > range.get() * range.get()) {
                cancel();
                return true;
            }

            BlockState current = mc.level.getBlockState(position);
            if (current.isAir()) {
                lastBrokenPos = position;
                lastDirection = direction;
                return true;
            }

            int toolSlot = getTool(position);
            int activeSlot = toolSlot != -1 ? toolSlot : mc.player.getInventory().getSelectedSlot();

            float destroySpeed = mc.player.getInventory().getItem(activeSlot).getDestroySpeed(current);
            float strength = (destroySpeed / current.getDestroySpeed(mc.level, position) / 30f) * speed.get().floatValue();

            progress = Math.min(1.0f, progress + strength);

            if (progress >= 1.0f) {
                int previousSlot = mc.player.getInventory().getSelectedSlot();

                // Fixed: Bypassed private .selected fields entirely by processing hotbar switches via InvUtils
                if (toolSlot != -1 && switchMode.get() != SwitchMode.None) {
                    InvUtils.swap(toolSlot, false);
                }

                mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction));

                if (grim.get()) {
                    mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position.above(500), direction));
                }

                if (toolSlot != -1 && switchMode.get() != SwitchMode.None) {
                    InvUtils.swap(previousSlot, false);
                }

                lastBrokenPos = position;
                lastDirection = direction;
                return true;
            }

            return false;
        }

        public void cancel() {
            if (mc.getConnection() == null) return;
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position, direction));
            this.progress = 0.0f;
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (primary != null) renderBlockBox(event, primary.position, primary.progress);
        if (doubleMine.get() && secondary != null) renderBlockBox(event, secondary.position, secondary.progress);
    }

    private void renderBlockBox(Render3DEvent event, BlockPos pos, float blockProgress) {
        double size = Math.min(1.0, blockProgress);
        int red = (int) ((1.0 - size) * 255);
        int green = (int) (size * 255);

        Color sideColor = new Color(red, green, 0, 75);
        Color lineColor = new Color(red, green, 0, 235);

        event.renderer.box(pos.getX() + 0.5 - (size / 2), pos.getY() + 0.5 - (size / 2), pos.getZ() + 0.5 - (size / 2),
            pos.getX() + 0.5 + (size / 2), pos.getY() + 0.5 + (size / 2), pos.getZ() + 0.5 + (size / 2),
            sideColor, lineColor, shapeMode.get(), 0);
    }
}
