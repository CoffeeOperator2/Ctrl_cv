package com.example.addon.modules;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.BreakIndicators;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

public class SpeedMinev1 extends Module {
    public enum SwapMode { None, Normal, Silent }
    public enum RenderStyle { Grow, Static }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSwap = settings.createGroup("Swap");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> stopThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("stop-threshold")
        .description("Progress at which Stop is sent. Vanilla servers accept ~0.7.")
        .defaultValue(0.7)
        .min(0.7)
        .max(1.0)
        .sliderMax(1.0)
        .build()
    );

    // SpeedMinev1: add this method
    public boolean isExtraBreak() {
        return extraBreak.get();
    }

    private final Setting<Boolean> tpsSync = sgGeneral.add(new BoolSetting.Builder()
        .name("tps-sync")
        .description("Scales progress by server TPS to avoid early Stop on lag.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> noBreakDelay = sgGeneral.add(new BoolSetting.Builder()
        .name("no-break-delay")
        .description("Skips the delay between blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between blocks in ticks.")
        .defaultValue(1)
        .min(0)
        .visible(() -> !noBreakDelay.get())
        .build()
    );

    private final Setting<Boolean> extraBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("extra-break")
        .description("Mines two blocks at once.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> antiQueue = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-queue")
        .description("Caps targets (1, or 2 with extra-break) by dropping the oldest.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rebreak = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-rebreak")
        .description("Re-mines when the block type changes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Keybind> rebreakBind = sgGeneral.add(new KeybindSetting.Builder()
        .name("rebreak-bind")
        .description("Hold this key to rebreak. If bound, rebreaking only triggers while held.")
        .defaultValue(Keybind.none())
        .visible(rebreak::get)
        .build()
    );

    private final Setting<Boolean> instantRebreak = sgGeneral.add(new BoolSetting.Builder()
        .name("instant-rebreak")
        .description("Sends STOP -> START -> STOP the moment a broken block is replaced for instant pop.")
        .defaultValue(true)
        .visible(rebreak::get)
        .build()
    );

    private final Setting<Boolean> retryStop = sgGeneral.add(new BoolSetting.Builder()
        .name("retry-stop")
        .description("Resends Stop every tick until the block breaks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates toward the block before starting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> obscure = sgGeneral.add(new BoolSetting.Builder()
        .name("obscure-breaking-progress")
        .description("Spams abort packets to hide progress.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SwapMode> swapMode = sgSwap.add(new EnumSetting.Builder<SwapMode>()
        .name("swap-mode")
        .description("How the best tool is selected for Stop.")
        .defaultValue(SwapMode.Silent)
        .build()
    );

    private final Setting<Boolean> notOnUse = sgSwap.add(new BoolSetting.Builder()
        .name("not-on-use")
        .description("Won't swap while using an item.")
        .defaultValue(true)
        .visible(() -> swapMode.get() != SwapMode.None)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders mined blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<RenderStyle> renderStyle = sgRender.add(new EnumSetting.Builder<RenderStyle>()
        .name("render-style")
        .description("Grow expands a red-to-green box from the center; Static draws a full block.")
        .defaultValue(RenderStyle.Grow)
        .visible(render::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<Integer> growSideAlpha = sgRender.add(new IntSetting.Builder()
        .name("grow-side-alpha")
        .description("Side alpha opacity for the expanding gradient box.")
        .defaultValue(75)
        .range(0, 255)
        .sliderRange(0, 255)
        .visible(() -> render.get() && renderStyle.get() == RenderStyle.Grow)
        .build()
    );

    private final Setting<Integer> growLineAlpha = sgRender.add(new IntSetting.Builder()
        .name("grow-line-alpha")
        .description("Line alpha opacity for the expanding gradient box.")
        .defaultValue(235)
        .range(0, 255)
        .sliderRange(0, 255)
        .visible(() -> render.get() && renderStyle.get() == RenderStyle.Grow)
        .build()
    );

    private final Setting<SettingColor> readySideColor = sgRender.add(new ColorSetting.Builder()
        .name("ready-side-color")
        .description("Side color when ready.")
        .defaultValue(new SettingColor(0, 204, 0, 10))
        .visible(() -> render.get() && renderStyle.get() == RenderStyle.Static)
        .build()
    );

    private final Setting<SettingColor> readyLineColor = sgRender.add(new ColorSetting.Builder()
        .name("ready-line-color")
        .description("Line color when ready.")
        .defaultValue(new SettingColor(0, 204, 0, 255))
        .visible(() -> render.get() && renderStyle.get() == RenderStyle.Static)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Side color while mining.")
        .defaultValue(new SettingColor(204, 0, 0, 10))
        .visible(() -> render.get() && renderStyle.get() == RenderStyle.Static)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color while mining.")
        .defaultValue(new SettingColor(204, 0, 0, 255))
        .visible(() -> render.get() && renderStyle.get() == RenderStyle.Static)
        .build()
    );

    private final Pool<MyBlock> blockPool = new Pool<>(MyBlock::new);
    public final List<MyBlock> blocks = new ArrayList<>();
    private boolean normalSwapped;

    public SpeedMinev1() {
        super(Categories.World, "speed-mine-v1", "Packet miner with early Stop, TPS sync, silent swap and instant rebreak.");
    }

    @Override
    public void onActivate() {
        normalSwapped = false;
    }

    @Override
    public void onDeactivate() {
        blockPool.freeAll(blocks);
        blocks.clear();
        if (normalSwapped) InvUtils.swapBack();
        normalSwapped = false;
    }

    private boolean canRebreakNow() {
        if (!rebreak.get()) return false;
        if (rebreakBind.get().isSet()) return rebreakBind.get().isPressed();
        return true;
    }

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (!BlockUtils.canBreak(event.blockPos)) return;
        event.cancel();
        addTarget(event.blockPos, event.direction);
    }

    public void addTarget(BlockPos pos, Direction direction) {
        if (mc.level == null || mc.player == null) return;
        if (!BlockUtils.canBreak(pos) || isMiningBlock(pos)) return;

        if (antiQueue.get()) {
            int max = extraBreak.get() ? 2 : 1;
            while (blocks.size() >= max) blockPool.free(blocks.remove(0));
        }

        blocks.add(blockPool.get().set(pos, direction));
    }

    public void removeTarget(BlockPos pos) {
        blocks.removeIf(b -> {
            if (!b.blockPos.equals(pos)) return false;
            blockPool.free(b);
            return true;
        });
    }

    public boolean isMiningBlock(BlockPos pos) {
        for (MyBlock b : blocks) if (b.blockPos.equals(pos)) return true;
        return false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (normalSwapped) {
            InvUtils.swapBack();
            normalSwapped = false;
        }

        blocks.removeIf(b -> {
            if (!b.shouldRemove()) return false;
            blockPool.free(b);
            return true;
        });

        if (blocks.isEmpty()) return;

        float scale = tpsSync.get() ? Mth.clamp(TickRate.INSTANCE.getTickRate() / 20f, 0.05f, 1f) : 1f;

        for (MyBlock b : blocks) {
            b.mine();
            if (!b.mining) continue;
            b.tick(scale);
            trySendStop(b);
        }
    }

    private void trySendStop(MyBlock b) {
        if (b.progress < stopThreshold.get() || (b.stopSent && !retryStop.get())) return;

        SwapMode mode = swapMode.get();
        int selected = mc.player.getInventory().getSelectedSlot();
        boolean swap = mode != SwapMode.None && b.toolFound && b.toolSlot != selected
            && (!mc.player.isUsingItem() || !notOnUse.get());

        if (swap) {
            if (mode == SwapMode.Silent) mc.player.connection.send(new ServerboundSetCarriedItemPacket(b.toolSlot));
            else if (InvUtils.swap(b.toolSlot, true)) normalSwapped = true;
        }

        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, b.blockPos, b.direction
        ));

        if (swap && mode == SwapMode.Silent) mc.player.connection.send(new ServerboundSetCarriedItemPacket(selected));
        b.stopSent = true;
    }

    private void fireInstantRebreakSequence(MyBlock b) {
        SwapMode mode = swapMode.get();
        int selected = mc.player.getInventory().getSelectedSlot();
        boolean swap = mode != SwapMode.None && b.toolFound && b.toolSlot != selected
            && (!mc.player.isUsingItem() || !notOnUse.get());

        if (swap) {
            if (mode == SwapMode.Silent) mc.player.connection.send(new ServerboundSetCarriedItemPacket(b.toolSlot));
            else if (InvUtils.swap(b.toolSlot, true)) normalSwapped = true;
        }

        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, b.blockPos, b.direction
        ));
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, b.blockPos, b.direction
        ));
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, b.blockPos, b.direction
        ));

        if (swap && mode == SwapMode.Silent) mc.player.connection.send(new ServerboundSetCarriedItemPacket(selected));

        b.mining = true;
        b.stopSent = true;
        b.broken = false;
        b.progress = stopThreshold.get();
        b.startTime = mc.player.tickCount;
    }

    @Override
    public String getInfoString() {
        if (blocks.isEmpty()) return null;
        return String.format("%.2f", Math.min(1.0, blocks.get(0).progress / stopThreshold.get()));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        BreakIndicators indicators = Modules.get().get(BreakIndicators.class);
        boolean hide = indicators != null && indicators.isActive() && indicators.packetMine.get();

        for (MyBlock b : blocks) if (!hide || !b.mining) b.render(event);
    }

    public class MyBlock {
        public BlockPos blockPos;
        public BlockState blockState;
        public Block block;
        public Direction direction;

        public int timer, startTime, toolSlot;
        public boolean mining, stopSent, toolFound, broken;
        public double progress;

        public MyBlock set(BlockPos pos, Direction dir) {
            blockPos = pos;
            direction = dir;
            restart(mc.level.getBlockState(pos));
            return this;
        }

        public void restart(BlockState state) {
            blockState = state;
            block = state.getBlock();
            FindItemResult fir = InvUtils.findFastestTool(state);
            toolFound = fir.found();
            toolSlot = toolFound ? fir.slot() : mc.player.getInventory().getSelectedSlot();
            mining = false;
            stopSent = false;
            broken = false;
            progress = 0;
            timer = noBreakDelay.get() ? 0 : delay.get();
            startTime = mc.player.tickCount;
        }

        public void tick(float scale) {
            int slot = swapMode.get() == SwapMode.None || !toolFound
                ? mc.player.getInventory().getSelectedSlot() : toolSlot;
            progress += BlockUtils.getBreakDelta(slot, blockState) * scale;
        }

        public boolean shouldRemove() {
            if (!PlayerUtils.isWithinReach(
                blockPos.getX() + direction.getStepX(),
                blockPos.getY() + direction.getStepY(),
                blockPos.getZ() + direction.getStepZ()
            )) return true;

            BlockState current = mc.level.getBlockState(blockPos);

            if (current.isAir()) {
                broken = true;
                if (rebreakBind.get().isSet() && rebreakBind.get().isPressed()) {
                    startTime = mc.player.tickCount;
                    return false;
                }
                return mc.player.tickCount - startTime > (instantRebreak.get() ? 200 : 70);
            }

            if (canRebreakNow() && ((broken && instantRebreak.get()) || (rebreak.get() && current.getBlock() != block))) {
                restart(current);
                if (instantRebreak.get()) fireInstantRebreakSequence(this);
                return false;
            }

            return progress > 2 && mc.player.tickCount - startTime > 60;
        }

        public boolean isReady() {
            return progress >= stopThreshold.get();
        }

        public void mine() {
            if (mining) {
                if (obscure.get()) sendAbort();
                return;
            }

            if (timer > 0) {
                timer--;
                return;
            }

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(blockPos), Rotations.getPitch(blockPos), 50, this::sendStart);
            else sendStart();
        }

        private void sendStart() {
            if (mining) return;

            mc.player.swing(InteractionHand.MAIN_HAND);
            startTime = mc.player.tickCount;
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, direction
            ));
            mining = true;
        }

        private void sendAbort() {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction
            ));
        }

        public void render(Render3DEvent event) {
            if (renderStyle.get() == RenderStyle.Grow) {
                double size = Math.min(1.0, progress / stopThreshold.get());
                int red = (int) ((1.0 - size) * 255);
                int green = (int) (size * 255);

                Color renderSide = new Color(red, green, 0, growSideAlpha.get());
                Color renderLine = new Color(red, green, 0, growLineAlpha.get());

                double half = size / 2.0;
                double cx = blockPos.getX() + 0.5;
                double cy = blockPos.getY() + 0.5;
                double cz = blockPos.getZ() + 0.5;

                event.renderer.box(
                    cx - half, cy - half, cz - half,
                    cx + half, cy + half, cz + half,
                    renderSide, renderLine, shapeMode.get(), 0
                );
                return;
            }

            VoxelShape shape = mc.level.getBlockState(blockPos).getShape(mc.level, blockPos);

            double x1 = blockPos.getX(), y1 = blockPos.getY(), z1 = blockPos.getZ();
            double x2 = x1 + 1, y2 = y1 + 1, z2 = z1 + 1;

            if (!shape.isEmpty()) {
                x1 += shape.min(Direction.Axis.X);
                y1 += shape.min(Direction.Axis.Y);
                z1 += shape.min(Direction.Axis.Z);
                x2 = blockPos.getX() + shape.max(Direction.Axis.X);
                y2 = blockPos.getY() + shape.max(Direction.Axis.Y);
                z2 = blockPos.getZ() + shape.max(Direction.Axis.Z);
            }

            if (isReady()) event.renderer.box(x1, y1, z1, x2, y2, z2, readySideColor.get(), readyLineColor.get(), shapeMode.get(), 0);
            else event.renderer.box(x1, y1, z1, x2, y2, z2, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
