package com.example.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class LadderAntiPhase extends Module {
    public enum Mode { Ladder, Vine, Scaffolding }

    private record Plan(BlockPos support, Direction face, Vec3 hit, AABB bounds) {}
    private record Flash(AABB box, int expiry) {}

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final Direction[] HORIZONTAL_UP = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTarget = settings.createGroup("Target");
    private final SettingGroup sgPlace = settings.createGroup("Placement");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("material").description("Ladder and vine attach to a wall. Scaffolding fills the whole cell.")
        .defaultValue(Mode.Ladder).build());

    private final Setting<Double> targetRange = sgTarget.add(new DoubleSetting.Builder()
        .name("target-range").description("The maximum distance to target players.")
        .defaultValue(10.0).min(0.0).sliderMax(30.0).build());

    private final Setting<Boolean> ignoreNaked = sgTarget.add(new BoolSetting.Builder()
        .name("ignore-naked").description("Skips players without armor.")
        .defaultValue(true).build());

    private final Setting<Boolean> noCrawl = sgTarget.add(new BoolSetting.Builder()
        .name("no-crawl").description("Skips crawling targets.")
        .defaultValue(false).build());

    private final Setting<Boolean> smart = sgTarget.add(new BoolSetting.Builder()
        .name("smart").description("Only targets slow players standing next to a wall.")
        .defaultValue(false).build());

    private final Setting<Double> maxSpeed = sgTarget.add(new DoubleSetting.Builder()
        .name("max-speed").description("Maximum target speed in km/h.")
        .defaultValue(18.0).min(0).sliderMax(40).visible(smart::get).build());

    private final Setting<Double> reach = sgPlace.add(new DoubleSetting.Builder()
        .name("reach").description("The range at which blocks can be placed.")
        .defaultValue(4.5).min(0.0).sliderMax(6.0).build());

    private final Setting<Integer> delay = sgPlace.add(new IntSetting.Builder()
        .name("delay").description("Ticks between placement cycles.")
        .defaultValue(2).min(0).sliderMax(10).build());

    private final Setting<Integer> retryDelay = sgPlace.add(new IntSetting.Builder()
        .name("retry-delay").description("Ticks before the same target is retried after a placement.")
        .defaultValue(5).min(0).sliderMax(20).build());

    private final Setting<Integer> perCycle = sgPlace.add(new IntSetting.Builder()
        .name("per-cycle").description("Maximum targets handled per cycle.")
        .defaultValue(3).range(1, 6).sliderRange(1, 6).build());

    private final Setting<Boolean> pull = sgPlace.add(new BoolSetting.Builder()
        .name("pull-from-inventory").description("Moves the item into the swap slot if it is not in your hotbar.")
        .defaultValue(true).build());

    private final Setting<Integer> swapSlot = sgPlace.add(new IntSetting.Builder()
        .name("swap-slot").description("Hotbar slot (1-9) used for pulled items.")
        .defaultValue(9).min(1).max(9).visible(pull::get).build());

    private final Setting<Boolean> sneak = sgPlace.add(new BoolSetting.Builder()
        .name("sneak").description("Sneaks while placing so interactable blocks don't open instead.")
        .defaultValue(true).build());

    private final Setting<Boolean> rotate = sgPlace.add(new BoolSetting.Builder()
        .name("rotate").description("Rotates toward the support block when placing.")
        .defaultValue(true).build());

    private final Setting<Boolean> pauseEating = sgPlace.add(new BoolSetting.Builder()
        .name("pause-eating").description("Pauses while using an item.")
        .defaultValue(true).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Renders where blocks are placed.")
        .defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both).visible(render::get).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("The side color.")
        .defaultValue(new SettingColor(0, 255, 100, 50)).visible(render::get).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("The line color.")
        .defaultValue(new SettingColor(0, 255, 100, 255)).visible(render::get).build());

    private final Map<UUID, Integer> cooldowns = new HashMap<>();
    private final List<Flash> flashes = new ArrayList<>();
    private int ticks, lastCycle;

    public LadderAntiPhase() {
        super(Categories.Combat, "ladder-anti-phase", "Places ladders, vines or scaffolding at a target's feet to block pearl phasing.");
    }

    @Override
    public void onActivate() {
        cooldowns.clear();
        flashes.clear();
        ticks = lastCycle = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        ticks++;
        if (ticks - lastCycle < delay.get()) return;
        if (pauseEating.get() && mc.player.isUsingItem()) return;
        lastCycle = ticks;

        List<Player> targets = new ArrayList<>();
        for (Player p : mc.level.players()) {
            if (validTarget(p)) targets.add(p);
        }
        if (targets.isEmpty()) return;

        targets.sort(Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)));

        List<Map.Entry<Player, Plan>> plans = new ArrayList<>();
        for (Player t : targets) {
            if (plans.size() >= perCycle.get()) break;

            Integer last = cooldowns.get(t.getUUID());
            if (last != null && ticks - last < retryDelay.get()) continue;

            BlockPos feet = feetOf(t);
            if (feet == null || !mc.level.getBlockState(feet).isAir()) continue;

            Plan plan = plan(t, feet);
            if (plan != null) plans.add(Map.entry(t, plan));
        }
        if (plans.isEmpty()) return;

        int slot = hotbarSlot(materialItem());
        if (slot == -1) return;

        for (Map.Entry<Player, Plan> entry : plans) {
            Plan plan = entry.getValue();

            Runnable action = () -> use(slot, plan);
            if (rotate.get()) Rotations.rotate(Rotations.getYaw(plan.hit()), Rotations.getPitch(plan.hit()), 25, action);
            else action.run();

            cooldowns.put(entry.getKey().getUUID(), ticks);
            flashes.add(new Flash(plan.bounds(), ticks + 10));
        }
    }

    private boolean validTarget(Player p) {
        if (p == mc.player || !p.isAlive() || p.isSpectator() || Friends.get().isFriend(p)) return false;
        if (p.distanceTo(mc.player) > targetRange.get()) return false;
        if (p.isSwimming() || !p.onGround() || feetOf(p) == null) return false;
        if (ignoreNaked.get() && p.getArmorValue() == 0) return false;
        if (noCrawl.get() && p.getBbHeight() <= 1.0f) return false;

        if (smart.get()) {
            double speed = Math.hypot(p.getX() - p.xo, p.getZ() - p.zo) * 72.0;
            if (speed > maxSpeed.get()) return false;
            if (mc.level.noBlockCollision(p, p.getBoundingBox().inflate(0.08, 0, 0.08))) return false;
        }

        return true;
    }

    private BlockPos feetOf(Player p) {
        AABB bb = p.getBoundingBox();
        int x1 = Mth.floor(bb.minX + 1e-7), x2 = Mth.floor(bb.maxX - 1e-7);
        int z1 = Mth.floor(bb.minZ + 1e-7), z2 = Mth.floor(bb.maxZ - 1e-7);

        if (x1 != x2 || z1 != z2) return null;
        return new BlockPos(x1, Mth.floor(bb.minY + 1e-7), z1);
    }

    private Plan plan(Player t, BlockPos feet) {
        Vec3 eye = mc.player.getEyePosition();
        Plan best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction toSupport : candidates()) {
            BlockPos support = feet.relative(toSupport);
            BlockState state = mc.level.getBlockState(support);
            Direction face = toSupport.getOpposite();

            if (state.canBeReplaced() || !state.isFaceSturdy(mc.level, support, face)) continue;

            AABB bounds = bounds(feet, toSupport);
            if (mode.get() == Mode.Ladder && t.getBoundingBox().intersects(bounds)) continue;

            Vec3 hit = Vec3.atCenterOf(support).relative(face, 0.5);
            if (eye.distanceTo(hit) > reach.get()) continue;

            double dist = t.position().distanceToSqr(Vec3.atCenterOf(support));
            if (dist < bestDist) {
                bestDist = dist;
                best = new Plan(support, face, hit, bounds);
            }
        }

        return best;
    }

    private Direction[] candidates() {
        return switch (mode.get()) {
            case Ladder -> HORIZONTAL;
            case Vine -> HORIZONTAL_UP;
            case Scaffolding -> Direction.values();
        };
    }

    private AABB bounds(BlockPos feet, Direction toSupport) {
        double x1 = feet.getX(), y1 = feet.getY(), z1 = feet.getZ();
        double x2 = x1 + 1, y2 = y1 + 1, z2 = z1 + 1;

        if (mode.get() == Mode.Scaffolding) return new AABB(x1, y1, z1, x2, y2, z2);

        double t = mode.get() == Mode.Ladder ? 3.0 / 16.0 : 1.0 / 16.0;

        switch (toSupport) {
            case EAST -> x1 = x2 - t;
            case WEST -> x2 = x1 + t;
            case SOUTH -> z1 = z2 - t;
            case NORTH -> z2 = z1 + t;
            case UP -> y1 = y2 - t;
            default -> {}
        }

        return new AABB(x1, y1, z1, x2, y2, z2);
    }

    private Item materialItem() {
        return switch (mode.get()) {
            case Ladder -> Items.LADDER;
            case Vine -> Items.VINE;
            case Scaffolding -> Items.SCAFFOLDING;
        };
    }

    private int hotbarSlot(Item item) {
        FindItemResult hotbar = InvUtils.findInHotbar(item);
        if (hotbar.found()) return hotbar.slot();
        if (!pull.get()) return -1;

        FindItemResult inventory = InvUtils.find(item);
        if (!inventory.found()) return -1;

        int dest = swapSlot.get() - 1;
        InvUtils.move().from(inventory.slot()).toHotbar(dest);
        return dest;
    }

    private void use(int slot, Plan plan) {
        boolean shift = sneak.get() && !mc.player.isShiftKeyDown();
        int prev = mc.player.getInventory().getSelectedSlot();
        BlockHitResult hit = new BlockHitResult(plan.hit(), plan.face(), plan.support(), false);

        if (shift) sendInput(true);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
        mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(prev));
        if (shift) sendInput(mc.options.keyShift.isDown());
    }

    private void sendInput(boolean shift) {
        var o = mc.options;
        mc.player.connection.send(new ServerboundPlayerInputPacket(new Input(
            o.keyUp.isDown(), o.keyDown.isDown(), o.keyLeft.isDown(), o.keyRight.isDown(),
            o.keyJump.isDown(), shift, o.keySprint.isDown()
        )));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        flashes.removeIf(f -> ticks > f.expiry());

        for (Flash f : flashes) {
            AABB b = f.box();
            event.renderer.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @Override
    public String getInfoString() {
        return mode.get().name();
    }
}
