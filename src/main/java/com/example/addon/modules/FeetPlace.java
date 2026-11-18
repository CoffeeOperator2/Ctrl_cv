package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Predicate;

public class FeetPlace extends Module {
    public enum Coverage {
        Feet(false, false), Eyes(true, false), Roof(false, true), Full(true, true);
        final boolean eyes, roof;
        Coverage(boolean eyes, boolean roof) { this.eyes = eyes; this.roof = roof; }
    }

    public enum DisableOn {
        Never(false, false), Jump(true, false), Height(false, true), Both(true, true);
        final boolean jump, height;
        DisableOn(boolean jump, boolean height) { this.jump = jump; this.height = height; }
    }

    public enum CenterMode { Teleport, NCP, None }
    public enum CrystalHandling { Off, Attack }
    public enum Sequential { None, Strict, Strong }
    public enum IgnoreMode { None, Mining, Enemy, Both }

    private static final double ATTACK_RANGE = 5.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgShape = settings.createGroup("Shape");
    private final SettingGroup sgPlace = settings.createGroup("Placement");
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> blocksSetting = sgGeneral.add(new BlockListSetting.Builder().name("blocks").defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN).build());
    private final Setting<Boolean> onlyObby = sgGeneral.add(new BoolSetting.Builder().name("only-obby").description("Ignores the block list and uses obsidian only.").defaultValue(false).build());
    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder().name("blocks-per-tick").defaultValue(4).min(1).sliderMax(8).build());
    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder().name("place-delay").defaultValue(0).min(0).sliderMax(10).build());
    private final Setting<Double> shiftDelay = sgGeneral.add(new DoubleSetting.Builder().name("shift-delay").description("Ticks before a position is retried.").defaultValue(1.0).min(0).sliderMax(5).build());
    private final Setting<Integer> swapSlot = sgGeneral.add(new IntSetting.Builder().name("swap-slot").description("Hotbar slot (1-9) for blocks pulled from the inventory.").defaultValue(9).min(1).max(9).build());

    private final Setting<Coverage> coverage = sgShape.add(new EnumSetting.Builder<Coverage>().name("coverage").defaultValue(Coverage.Feet).build());
    private final Setting<Boolean> support = sgShape.add(new BoolSetting.Builder().name("support").defaultValue(true).build());
    private final Setting<Boolean> smartFace = sgShape.add(new BoolSetting.Builder().name("smart-face").description("Adds an eye-level layer once 4+ crystals sit on your surround.").defaultValue(false).build());

    private final Setting<Boolean> rotate = sgPlace.add(new BoolSetting.Builder().name("rotate").defaultValue(false).build());
    private final Setting<Boolean> airplace = sgPlace.add(new BoolSetting.Builder().name("airplace").description("When off, places a support block first if a spot has nothing to click.").defaultValue(false).build());
    private final Setting<Boolean> multitask = sgPlace.add(new BoolSetting.Builder().name("multitask").defaultValue(false).build());
    private final Setting<Sequential> sequential = sgPlace.add(new EnumSetting.Builder<Sequential>().name("sequential").description("Places again on each block update. Strict once per tick, Strong every update.").defaultValue(Sequential.Strict).build());
    private final Setting<Boolean> packetReaction = sgPlace.add(new BoolSetting.Builder().name("packet-reaction").description("Refills immediately when a surround block is removed or explosions hit.").defaultValue(true).build());
    private final Setting<CrystalHandling> crystalHandling = sgPlace.add(new EnumSetting.Builder<CrystalHandling>().name("crystal-handling").defaultValue(CrystalHandling.Attack).build());
    private final Setting<Boolean> instant = sgPlace.add(new BoolSetting.Builder().name("instant").description("Places in the same cycle as the crystal attack and reacts to crystal spawns.").defaultValue(true).build());
    private final Setting<IgnoreMode> blockIgnore = sgPlace.add(new EnumSetting.Builder<IgnoreMode>().name("block-ignore").description("Skips blocks SpeedMinev1 is mining and/or spots around nearby enemies.").defaultValue(IgnoreMode.Mining).build());

    private final Setting<CenterMode> centerMode = sgMovement.add(new EnumSetting.Builder<CenterMode>().name("center-mode").defaultValue(CenterMode.NCP).build());
    private final Setting<Boolean> onlyOnGround = sgMovement.add(new BoolSetting.Builder().name("only-on-ground").defaultValue(false).build());
    private final Setting<DisableOn> disableOn = sgMovement.add(new EnumSetting.Builder<DisableOn>().name("disable-on").defaultValue(DisableOn.Both).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder().name("render").defaultValue(true).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").defaultValue(ShapeMode.Both).visible(render::get).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder().name("side-color").defaultValue(new SettingColor(0, 255, 0, 15)).visible(() -> render.get() && shapeMode.get().sides()).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(0, 255, 0, 255)).visible(() -> render.get() && shapeMode.get().lines()).build());

    private final Set<BlockPos> targets = new LinkedHashSet<>();
    private final Map<BlockPos, Long> lastAttempt = new HashMap<>();
    private final Set<Integer> attacked = new HashSet<>();
    private final Set<Integer> counted = new HashSet<>();

    private Player trackedPlayer;
    private double baselineY;
    private int ticks, delayTimer, lagbackUntil, crystalCount;
    private boolean centered, sequentialDone;

    public FeetPlace() {
        super(AddonTemplate.CATEGORY, "feet-place", "Surround with lag-back handling, sequential placement, blocker handling and crystal handling.");
    }

    @Override
    public void onActivate() {
        ticks = delayTimer = lagbackUntil = crystalCount = 0;
        centered = sequentialDone = false;
        lastAttempt.clear();
        targets.clear();
        attacked.clear();
        counted.clear();
        trackedPlayer = mc.player;

        if (mc.player != null && mc.level != null) {
            baselineY = mc.player.getY();
            rebuildTargets();
            countCrystals();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        ticks++;
        sequentialDone = false;
        attacked.clear();

        if (mc.player != trackedPlayer || mc.player.isDeadOrDying() || shouldDisable()) {
            toggle();
            return;
        }

        if (!centered && centerMode.get() != CenterMode.None && !mc.player.isPassenger()) centered = centerPlayer();

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        if (cycle() > 0) delayTimer = placeDelay.get();
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;
        Packet<?> packet = event.packet;

        if (packet instanceof ClientboundPlayerPositionPacket position) {
            double y = position.change().position().y;
            later(() -> {
                baselineY = y;
                lagbackUntil = ticks + 5;
            });
        } else if (packet instanceof ClientboundBlockUpdatePacket update) {
            BlockPos at = update.getPos();
            boolean opened = update.getBlockState().canBeReplaced();
            later(() -> onTargetUpdate(at, opened));
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket || packet instanceof ClientboundExplodePacket) {
            later(() -> runSequential(true));
            // in onReceive, replace the crystal branch
        } else if (packet instanceof ClientboundAddEntityPacket spawn) {
            int id = spawn.getId();
            later(() -> {
                if (mc.level.getEntity(id) instanceof EndCrystal) onCrystalSpawn();
            });
        }
    }

    private void later(Runnable runnable) {
        mc.execute(() -> mc.execute(() -> {
            if (isActive() && mc.player != null && mc.level != null) runnable.run();
        }));
    }

    private void onTargetUpdate(BlockPos pos, boolean opened) {
        if (targets.contains(pos)) runSequential(opened);
    }

    private void runSequential(boolean opened) {
        Sequential mode = sequential.get();
        boolean sequentialRun = mode == Sequential.Strong || (mode == Sequential.Strict && !sequentialDone);

        if (sequentialRun || (opened && packetReaction.get())) sequentialDone = cycle() > 0;
    }

    private void onCrystalSpawn() {
        countCrystals();
        if (instant.get() && crystalHandling.get() == CrystalHandling.Attack) cycle();
    }

    private void countCrystals() {
        if (!smartFace.get()) return;

        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, mc.player.getBoundingBox().inflate(8))) {
            if (targets.contains(crystal.blockPosition().below()) && counted.add(crystal.getId())) crystalCount++;
        }
    }

    private boolean shouldDisable() {
        if (ticks < lagbackUntil) return false;

        return (disableOn.get().jump && mc.options.keyJump.isDown())
            || (disableOn.get().height && Math.abs(mc.player.getY() - baselineY) > 0.05);
    }

    private boolean canAct() {
        if (mc.player.isPassenger() || (onlyOnGround.get() && !mc.player.onGround())) return false;
        return multitask.get() || !mc.player.isUsingItem();
    }

    private int cycle() {
        if (mc.player == null || mc.level == null || !canAct()) return 0;

        rebuildTargets();
        return fill();
    }

    private boolean centerPlayer() {
        if (!mc.player.onGround()) return false;
        if (footprint().size() == 1) return true;

        if (centerMode.get() == CenterMode.Teleport) {
            PlayerUtils.centerPlayer();
            return true;
        }

        double dx = Math.floor(mc.player.getX()) + 0.5 - mc.player.getX();
        double dz = Math.floor(mc.player.getZ()) + 0.5 - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) return true;

        double step = Math.min(dist, 0.3);
        mc.player.setPos(mc.player.getX() + dx / dist * step, mc.player.getY(), mc.player.getZ() + dz / dist * step);
        mc.player.setDeltaMovement(0, mc.player.getDeltaMovement().y, 0);

        return step >= dist;
    }

    private Set<BlockPos> footprint() {
        AABB bb = mc.player.getBoundingBox();
        int y = mc.player.blockPosition().getY();
        Set<BlockPos> result = new LinkedHashSet<>();

        for (int x = Mth.floor(bb.minX + 1e-7); x <= Mth.floor(bb.maxX - 1e-7); x++) {
            for (int z = Mth.floor(bb.minZ + 1e-7); z <= Mth.floor(bb.maxZ - 1e-7); z++) {
                result.add(new BlockPos(x, y, z));
            }
        }

        return result;
    }

    private Set<BlockPos> occupied(Player player) {
        AABB bb = player.getBoundingBox();
        Set<BlockPos> result = new HashSet<>();

        for (int x = Mth.floor(bb.minX + 1e-7); x <= Mth.floor(bb.maxX - 1e-7); x++) {
            for (int y = Mth.floor(bb.minY + 1e-7); y <= Mth.floor(bb.maxY - 1e-7); y++) {
                for (int z = Mth.floor(bb.minZ + 1e-7); z <= Mth.floor(bb.maxZ - 1e-7); z++) {
                    result.add(new BlockPos(x, y, z));
                }
            }
        }

        return result;
    }

    private void rebuildTargets() {
        targets.clear();

        Set<BlockPos> feet = footprint();
        int height = Math.max(1, (int) Math.ceil(mc.player.getBbHeight()));
        boolean eyes = (coverage.get().eyes || (smartFace.get() && crystalCount > 3)) && height > 1;

        for (BlockPos foot : feet) {
            if (support.get()) targets.add(foot.below());

            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos wall = foot.relative(side);
                if (feet.contains(wall)) continue;

                targets.add(wall);
                if (eyes) targets.add(wall.above());
            }

            if (coverage.get().roof) targets.add(foot.above(height));
        }

        for (BlockPos pos : new ArrayList<>(targets)) {
            if (!blockedByEntity(pos)) continue;

            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos around = pos.relative(side);
                if (feet.contains(around)) continue;

                targets.add(around);
                if (support.get()) targets.add(around.below());
            }
        }
    }

    private boolean blockedByEntity(BlockPos pos) {
        if (!mc.level.getBlockState(pos).canBeReplaced()) return false;
        return !mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), e -> e != mc.player).isEmpty();
    }

    private int fill() {
        FindItemResult blocks = findBlocks();
        if (!blocks.found()) return 0;

        Set<BlockPos> enemies = blockIgnore.get() == IgnoreMode.Enemy || blockIgnore.get() == IgnoreMode.Both
            ? enemyPositions() : Collections.emptySet();

        List<BlockPos> order = new ArrayList<>(targets);
        order.removeIf(pos -> ignored(pos, enemies));

        Vec3 velocity = mc.player.getDeltaMovement();
        float yaw = velocity.horizontalDistanceSqr() > 0.01
            ? (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z))
            : mc.player.getYRot();
        order.sort(Comparator.comparingDouble(pos -> Mth.degreesDifferenceAbs(yaw, (float) Rotations.getYaw(pos)) + pos.getY() * 0.001));

        long now = System.nanoTime();
        long gap = (long) (shiftDelay.get() * 50_000_000L);
        lastAttempt.values().removeIf(time -> now - time > 1_000_000_000L);

        int placed = 0;

        for (BlockPos pos : order) {
            if (placed >= blocksPerTick.get()) break;

            Long previous = lastAttempt.get(pos);
            if (previous != null && now - previous < gap) continue;
            if (!mc.level.getBlockState(pos).canBeReplaced() || !handleCrystals(pos)) continue;

            BlockPos place = pos;
            if (!airplace.get() && !hasSupport(pos)) {
                place = findSupport(pos);
                if (place == null) continue;
            }

            if (BlockUtils.place(place, blocks, rotate.get(), 50, true, false, true)) {
                lastAttempt.put(place, now);
                placed++;
            }
        }

        return placed;
    }

    private boolean handleCrystals(BlockPos pos) {
        if (crystalHandling.get() == CrystalHandling.Off) return true;

        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(pos))) {
            if (!crystal.isAlive() || attacked.contains(crystal.getId())) continue;
            if (mc.player.distanceTo(crystal) > ATTACK_RANGE) return false;

            attack(crystal);
            attacked.add(crystal.getId());
            if (!instant.get()) return false;
        }

        return true;
    }

    private void attack(EndCrystal crystal) {
        Runnable action = () -> {
            mc.gameMode.attack(mc.player, crystal);
            mc.player.swing(InteractionHand.MAIN_HAND);
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(crystal), Rotations.getPitch(crystal), 50, action);
        else action.run();
    }

    private boolean hasSupport(BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (!mc.level.getBlockState(pos.relative(d)).canBeReplaced()) return true;
        }
        return false;
    }

    private BlockPos findSupport(BlockPos pos) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

        for (Direction d : order) {
            BlockPos candidate = pos.relative(d);
            if (mc.level.getBlockState(candidate).canBeReplaced() && hasSupport(candidate)) return candidate;
        }

        return null;
    }

    private boolean ignored(BlockPos pos, Set<BlockPos> enemies) {
        IgnoreMode mode = blockIgnore.get();
        if (mode == IgnoreMode.None) return false;

        if (mode == IgnoreMode.Mining || mode == IgnoreMode.Both) {
            SpeedMinev1 speedMine = Modules.get().get(SpeedMinev1.class);
            if (speedMine != null && speedMine.isActive() && speedMine.isMiningBlock(pos)) return true;
        }

        return (mode == IgnoreMode.Enemy || mode == IgnoreMode.Both) && enemies.contains(pos);
    }

    private Set<BlockPos> enemyPositions() {
        Set<BlockPos> result = new HashSet<>();

        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.distanceTo(mc.player) > 4 || Friends.get().isFriend(player)) continue;

            for (BlockPos cell : occupied(player)) {
                if (!mc.level.getBlockState(cell).canBeReplaced()) continue;
                for (Direction d : Direction.Plane.HORIZONTAL) result.add(cell.relative(d));
            }
        }

        return result;
    }

    private boolean allowed(Block block) {
        return onlyObby.get() ? block == Blocks.OBSIDIAN : blocksSetting.get().contains(block);
    }

    private FindItemResult findBlocks() {
        Predicate<ItemStack> match = stack -> stack.getItem() instanceof BlockItem item && allowed(item.getBlock());

        FindItemResult hotbar = InvUtils.findInHotbar(match);
        if (hotbar.found()) return hotbar;

        FindItemResult inventory = InvUtils.find(match);
        if (!inventory.found()) return hotbar;

        int dest = swapSlot.get() - 1;
        InvUtils.move().from(inventory.slot()).toHotbar(dest);
        return new FindItemResult(dest, inventory.count());
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        for (BlockPos pos : targets) {
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @Override
    public String getInfoString() {
        long open = targets.stream().filter(pos -> mc.level != null && mc.level.getBlockState(pos).canBeReplaced()).count();
        return open == 0 ? "Filled" : open + " open";
    }
}
