package com.example.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class AutoMine extends Module {
    public enum PushMode { Shulker, Piston }

    private enum Kind {
        Ender(false), Terrain(false), Self(false), Head(true), Fish(true), Feet(true), Burrow(true);

        final boolean player;
        Kind(boolean player) { this.player = player; }
    }

    private static class Candidates {
        int priority = -1;
        Kind kind;
        BlockPos primary, secondary;
        final Set<BlockPos> offered = new HashSet<>();

        void offer(int priority, Kind kind, BlockPos primary, BlockPos secondary) {
            if (primary != null) offered.add(primary);
            if (secondary != null) offered.add(secondary);

            if (priority > this.priority) {
                this.priority = priority;
                this.kind = kind;
                this.primary = primary;
                this.secondary = secondary;
            }
        }
    }

    private record Pending(BlockPos pos, Direction dir, int due) {}

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargets = settings.createGroup("Targets");
    private final SettingGroup sgPush = settings.createGroup("Push");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range").description("The radius in which players get targeted.")
        .defaultValue(6.0).min(0).sliderMax(10).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Rotates toward the block before mining it.")
        .defaultValue(true).build());

    private final Setting<Boolean> raytrace = sgGeneral.add(new BoolSetting.Builder()
        .name("raytrace").description("Rotates toward a visible point on the block.")
        .defaultValue(false).visible(rotate::get).build());

    private final Setting<Boolean> strictDirection = sgGeneral.add(new BoolSetting.Builder()
        .name("strict-direction").description("Mines from a face you can actually see.")
        .defaultValue(false).build());

    private final Setting<Boolean> ignoreNaked = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-naked").description("Skips players without armor.")
        .defaultValue(false).build());

    private final Setting<Boolean> assistant = sgGeneral.add(new BoolSetting.Builder()
        .name("assistant").description("Leaves blocks you started mining by hand alone.")
        .defaultValue(false).build());

    private final Setting<Integer> extraDelay = sgGeneral.add(new IntSetting.Builder()
        .name("extra-break-delay").description("Ticks between the two blocks when SpeedMinev1 extra-break is on.")
        .defaultValue(1).min(0).sliderMax(6).build());

    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown").description("Ticks to wait after a target cycle before scanning again.")
        .defaultValue(10).min(0).sliderMax(40).build());

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Turns off after one target cycle.")
        .defaultValue(false).build());

    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-info").description("Sends status messages in chat.")
        .defaultValue(true).build());

    private final Setting<Boolean> burrow = sgTargets.add(new BoolSetting.Builder()
        .name("burrow").description("Mines the block a target is standing inside.")
        .defaultValue(true).build());

    private final Setting<Boolean> fish = sgTargets.add(new BoolSetting.Builder()
        .name("fish").description("Mines the support under a target standing inside an unbreakable block.")
        .defaultValue(false).build());

    private final Setting<Boolean> terrain = sgTargets.add(new BoolSetting.Builder()
        .name("terrain").description("Farms nearby obsidian when there is no target.")
        .defaultValue(false).build());

    private final Setting<Boolean> enderChests = sgTargets.add(new BoolSetting.Builder()
        .name("ender-chests").description("Mines nearby ender chests.")
        .defaultValue(true).build());

    private final Setting<Double> farmRange = sgTargets.add(new DoubleSetting.Builder()
        .name("farm-range").description("Range for ender chests and terrain obsidian.")
        .defaultValue(2.5).min(0.1).sliderMax(6).build());

    private final Setting<Boolean> head = sgTargets.add(new BoolSetting.Builder()
        .name("head").description("Mines the block above a target's head.")
        .defaultValue(false).build());

    private final Setting<Boolean> inCrawl = sgTargets.add(new BoolSetting.Builder()
        .name("in-crawl").description("Also mines above crawling targets.")
        .defaultValue(false).visible(head::get).build());

    private final Setting<Boolean> trapOverride = sgTargets.add(new BoolSetting.Builder()
        .name("trap-override").description("Mines the head block before the feet blocks.")
        .defaultValue(false).visible(head::get).build());

    private final Setting<Boolean> feet = sgTargets.add(new BoolSetting.Builder()
        .name("feet").description("Mines the blocks around a target's feet.")
        .defaultValue(true).build());

    private final Setting<Boolean> corners = sgTargets.add(new BoolSetting.Builder()
        .name("corners").description("Includes diagonal blocks around the target's feet.")
        .defaultValue(true).visible(feet::get).build());

    private final Setting<Boolean> safe = sgTargets.add(new BoolSetting.Builder()
        .name("safe").description("Only mines blocks with 600+ blast resistance (obsidian class).")
        .defaultValue(false).visible(feet::get).build());

    private final Setting<Boolean> self = sgTargets.add(new BoolSetting.Builder()
        .name("self").description("Mines your own overhead, face or floor blocks to escape traps.")
        .defaultValue(true).build());

    private final Setting<Boolean> face = sgTargets.add(new BoolSetting.Builder()
        .name("face").description("Mines a resistant block at your eye level.")
        .defaultValue(false).visible(self::get).build());

    private final Setting<Boolean> onlyCrawl = sgTargets.add(new BoolSetting.Builder()
        .name("only-crawl").description("Only self mines while crawling or when a face block exists.")
        .defaultValue(false).visible(self::get).build());

    private final Setting<Boolean> tryCrawl = sgTargets.add(new BoolSetting.Builder()
        .name("try-crawl").description("Mines your feet block to crawl out.")
        .defaultValue(false).visible(self::get).build());

    private final Setting<Boolean> instant = sgTargets.add(new BoolSetting.Builder()
        .name("instant").description("Keeps the last self block queued for a few ticks.")
        .defaultValue(false).visible(self::get).build());

    private final Setting<Boolean> down = sgTargets.add(new BoolSetting.Builder()
        .name("down").description("Mines the block below you when your surround floor is solid.")
        .defaultValue(true).visible(self::get).build());

    private final Setting<Boolean> downPriority = sgTargets.add(new BoolSetting.Builder()
        .name("down-priority").description("Mines down without checking the surround floor.")
        .defaultValue(false).visible(() -> self.get() && down.get()).build());

    private final Setting<Boolean> crystalSync = sgPush.add(new BoolSetting.Builder()
        .name("crystal-sync").description("Hands the broken surround spot to AutoCrystal instead of pushing the target.")
        .defaultValue(true).build());

    private final Setting<Boolean> forcePush = sgPush.add(new BoolSetting.Builder()
        .name("force-push").description("Displaces the target when their surround block breaks.")
        .defaultValue(true).build());

    private final Setting<PushMode> pushMode = sgPush.add(new EnumSetting.Builder<PushMode>()
        .name("push-mode").description("The displacement method to utilize.")
        .defaultValue(PushMode.Shulker).visible(forcePush::get).build());

    private final Setting<Boolean> support = sgPush.add(new BoolSetting.Builder()
        .name("support").description("Places obsidian below a feet block if floating.")
        .defaultValue(true).build());

    private final Setting<Boolean> renderBlock = sgRender.add(new BoolSetting.Builder()
        .name("render-block").description("Whether to render the blocks being broken.")
        .defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both).visible(renderBlock::get).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("The side color of the rendering.")
        .defaultValue(new SettingColor(225, 0, 0, 75))
        .visible(() -> renderBlock.get() && shapeMode.get().sides()).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("The line color of the rendering.")
        .defaultValue(new SettingColor(225, 0, 0, 255))
        .visible(() -> renderBlock.get() && shapeMode.get().lines()).build());

    private final List<Pending> pending = new ArrayList<>();
    private SpeedMinev1 speedMine;
    private Player target;
    private Kind primaryKind;
    private BlockPos primaryPos, secondaryPos, recentSelf;
    private int ticks, cooldown, pistonStage, primaryPriority, recentSelfTick, recentSelfPriority;

    public AutoMine() {
        super(Categories.Combat, "auto-mine", "Priority-based miner for enemy surrounds, burrows, traps, your own escape blocks and obsidian farming.");
    }

    @Override
    public void onActivate() {
        pending.clear();
        target = null;
        primaryKind = null;
        primaryPos = secondaryPos = recentSelf = null;
        ticks = cooldown = pistonStage = primaryPriority = recentSelfTick = recentSelfPriority = 0;
    }

    @Override
    public void onDeactivate() {
        SpeedMinev1 sm = Modules.get().get(SpeedMinev1.class);
        if (sm != null) {
            speedMine = sm;
            drop();
        } else {
            pending.clear();
            target = null;
            primaryPos = secondaryPos = null;
            primaryKind = null;
        }
    }

    public Player getMineTarget() {
        return target;
    }

    public BlockPos getMinedPos() {
        return primaryKind == Kind.Feet ? primaryPos : null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        ticks++;

        speedMine = Modules.get().get(SpeedMinev1.class);
        if (speedMine == null) {
            if (chatInfo.get()) error("SpeedMinev1 module not found.");
            toggle();
            return;
        }

        if (!pending.isEmpty()) {
            runPending();
            return;
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (handleBroken()) return;
        if (assistant.get() && userMining()) return;

        Player t = selectTarget();
        Candidates c = collect(t);

        BlockPos primary = c.primary != null ? c.primary : c.secondary;
        boolean active = primaryPos != null && !air(primaryPos);

        if (active) {
            boolean stale = primaryKind.player && (!c.offered.contains(primaryPos) || !valid(target));
            boolean outranked = c.priority > primaryPriority || c.priority >= 1500;

            if (!stale && !outranked) return;

            if (primary == null) {
                if (stale) drop();
                return;
            }
        } else if (primary == null) {
            return;
        }

        BlockPos secondary = c.primary != null ? c.secondary : null;
        if (!speedMine.isExtraBreak() || (secondary != null && secondary.equals(primary))) secondary = null;

        if (primary.equals(primaryPos) && Objects.equals(secondary, secondaryPos)) return;

        Direction dir = direction(primary);
        if (dir == null) return;

        Direction secDir = secondary == null ? null : direction(secondary);
        if (secondary != null && secDir == null) secondary = null;

        release();

        target = c.kind.player ? t : null;
        primaryPos = primary;
        secondaryPos = secondary;
        primaryKind = c.kind;
        primaryPriority = c.priority;

        if (!speedMine.isActive()) speedMine.toggle();
        if (c.kind == Kind.Feet) placeSupport(primary);

        if (secondary != null) schedule(secondary, secDir, 0);
        schedule(primary, dir, secondary != null ? extraDelay.get() : 0);

        if (chatInfo.get()) info("Mining " + c.kind.name().toLowerCase() + " block.");
    }

    private boolean handleBroken() {
        if (secondaryPos != null && air(secondaryPos)) {
            speedMine.removeTarget(secondaryPos);
            secondaryPos = null;
        }

        if (primaryPos == null || !air(primaryPos)) return false;

        Kind kind = primaryKind;

        if (kind == Kind.Feet && valid(target)) {
            push();
            return true;
        }

        if (kind.player) {
            finishCycle();
            return true;
        }

        release();
        return false;
    }

    private boolean userMining() {
        for (SpeedMinev1.MyBlock b : speedMine.blocks) {
            if (!b.blockPos.equals(primaryPos) && !b.blockPos.equals(secondaryPos) && !air(b.blockPos)) return true;
        }
        return false;
    }

    private Candidates collect(Player t) {
        Candidates c = new Candidates();

        if (enderChests.get()) enderChests(c);
        if (terrain.get() && t == null) terrain(c);

        if (t != null) {
            if (burrow.get()) burrow(c, t);
            if (fish.get()) fish(c, t);
            if (feet.get()) feet(c, t);
            if (head.get()) head(c, t);
        }

        if (self.get()) self(c, t);
        return c;
    }

    private void enderChests(Candidates c) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3 eye = mc.player.getEyePosition();
        int r = (int) Math.ceil(farmRange.get());
        BlockPos origin = BlockPos.containing(eye);

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!state(pos).is(Blocks.ENDER_CHEST) || !canMine(pos)) continue;

                    double dist = eye.distanceTo(Vec3.atCenterOf(pos));
                    if (dist > farmRange.get()) continue;
                    if (mining(pos)) dist -= 1000;

                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }

        if (best != null) c.offer(5, Kind.Ender, best, null);
    }

    private void terrain(Candidates c) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3 eye = mc.player.getEyePosition();
        BlockPos me = mc.player.blockPosition();
        int r = (int) Math.ceil(farmRange.get());
        BlockPos origin = BlockPos.containing(eye);

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!state(pos).is(Blocks.OBSIDIAN) || !canMine(pos)) continue;
                    if (Math.abs(pos.getX() - me.getX()) <= 1 && Math.abs(pos.getZ() - me.getZ()) <= 1
                        && pos.getY() >= me.getY() - 1 && pos.getY() <= me.getY() + 2) continue;

                    double dist = eye.distanceTo(Vec3.atCenterOf(pos));
                    if (dist > farmRange.get()) continue;
                    if (mining(pos)) dist -= 1000;

                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }

        if (best != null) c.offer(3, Kind.Terrain, best, null);
    }

    private void burrow(Candidates c, Player t) {
        List<BlockPos> list = burrowCells(t);
        if (list.isEmpty()) return;

        if (!speedMine.isExtraBreak()) c.offer(700, Kind.Burrow, list.get(0), null);
        else if (list.size() == 1) c.offer(500, Kind.Burrow, null, list.get(0));
        else c.offer(700, Kind.Burrow, list.get(0), list.get(1));
    }

    private List<BlockPos> burrowCells(Player t) {
        AABB bb = t.getBoundingBox();
        int feetY = Mth.floor(bb.minY + 1e-7);
        int height = Math.max(1, (int) Math.ceil(t.getBbHeight()));
        List<BlockPos> list = new ArrayList<>();

        for (int dy = 0; dy < height; dy++) {
            for (int x = Mth.floor(bb.minX + 1e-7); x <= Mth.floor(bb.maxX - 1e-7); x++) {
                for (int z = Mth.floor(bb.minZ + 1e-7); z <= Mth.floor(bb.maxZ - 1e-7); z++) {
                    BlockPos pos = new BlockPos(x, feetY + dy, z);
                    BlockState s = state(pos);

                    if (ignored(s) || s.getBlock().getExplosionResistance() < 600f) continue;
                    if (s.getCollisionShape(mc.level, pos).isEmpty() || !canMine(pos)) continue;

                    list.add(pos);
                }
            }
        }

        list.sort(Comparator.comparingDouble(p -> (mining(p) ? -1e6 : 0) + (p.getY() - feetY) * 1000.0 + t.distanceToSqr(Vec3.atCenterOf(p))));
        return list;
    }

    private void fish(Candidates c, Player t) {
        Set<BlockPos> cells = cells(t);
        if (cells.size() != 1) return;

        BlockPos pos = cells.iterator().next();
        if (air(pos) || canMine(pos)) return;

        BlockPos below = pos.below();
        if (!canMine(below)) return;

        if (speedMine.isExtraBreak()) c.offer(400, Kind.Fish, null, below);
        else c.offer(400, Kind.Fish, below, null);
    }

    private void feet(Candidates c, Player t) {
        Set<BlockPos> cells = cells(t);
        BlockPos city = EntityUtils.getCityBlock(t);
        List<BlockPos> list = new ArrayList<>();
        Vec3 eye = mc.player.getEyePosition();

        for (BlockPos pos : ring(cells, corners.get())) {
            BlockState s = state(pos);
            if (!canMine(pos) || ignored(s)) continue;
            if (safe.get() && s.getBlock().getExplosionResistance() < 600f) continue;
            list.add(pos);
        }

        if (list.isEmpty()) return;

        list.sort(Comparator.comparingDouble(p -> mining(p) ? -1e6 : p.equals(city) ? -1e3 : eye.distanceToSqr(Vec3.atCenterOf(p))));

        if (speedMine.isExtraBreak() && list.size() >= 2) c.offer(600, Kind.Feet, list.get(0), list.get(1));
        else c.offer(600, Kind.Feet, list.get(0), null);
    }

    private void head(Candidates c, Player t) {
        if (t.getBbHeight() < 1.0f && !inCrawl.get()) return;

        int h = (int) Math.ceil(t.getBbHeight());
        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos cell : cells(t)) {
            BlockPos pos = cell.above(h);
            if (!canMine(pos) || ignored(state(pos))) continue;

            double dist = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (dist < bestDist) {
                bestDist = dist;
                best = pos;
            }
        }

        if (best != null) c.offer(trapOverride.get() ? 650 : 300, Kind.Head, best, null);
    }

    private void self(Candidates c, Player t) {
        BlockPos faceBlock = faceCandidate();
        if (t == null && faceBlock == null) return;
        if (mc.player.isFallFlying()) return;

        if (instant.get() && recentSelf != null && ticks - recentSelfTick <= 3) {
            c.offer(recentSelfPriority, Kind.Self, recentSelf, null);
        }

        boolean low = lowProfile();
        if (onlyCrawl.get() && !low && faceBlock == null) return;

        BlockPos candidate = selfCandidate(faceBlock, low);
        if (candidate == null) return;

        int priority = !low && faceBlock == null ? 100 : 1500;
        if (tryCrawl.get() && !low && candidate.getY() == Math.round(mc.player.getY())) priority = 1000;

        c.offer(priority, Kind.Self, candidate, null);
        recentSelf = candidate;
        recentSelfPriority = priority;
        recentSelfTick = ticks;
    }

    private BlockPos faceCandidate() {
        if (!face.get() || lowProfile()) return null;

        BlockPos eye = BlockPos.containing(mc.player.getEyePosition());
        BlockState eyeState = state(eye);
        if (eyeState.isAir() || eyeState.getBlock().getExplosionResistance() < 600f || !canMine(eye)) return null;

        BlockPos feetPos = mc.player.blockPosition();
        if (!air(feetPos) && !canMine(feetPos)) return null;

        return eye;
    }

    private BlockPos selfCandidate(BlockPos faceBlock, boolean low) {
        BlockPos base = mc.player.blockPosition();
        BlockPos below = base.below();
        BlockPos overhead = BlockPos.containing(mc.player.position().add(0, mc.player.getBbHeight() - 0.001, 0)).above();

        if (tryCrawl.get() && !low && canMine(base) && (mining(base) || groundedResistant(overhead.below()))) return base;

        if (canMine(below) && (mining(below) || low) && shouldMineDown()) return below;

        if (low && closedUpperTrapdoor(overhead.below())) overhead = overhead.below();
        if (tryCrawl.get() && low) return null;
        if (faceBlock != null) overhead = faceBlock;

        return canMine(overhead) ? overhead : null;
    }

    private boolean shouldMineDown() {
        if (!down.get()) return false;
        if (downPriority.get()) return true;
        if (mc.level.isEmptyBlock(mc.player.blockPosition().below(2))) return false;

        for (BlockPos pos : ring(cellsOf(mc.player), false)) {
            BlockState s = state(pos.below());
            if (s.isAir() || s.getBlock().getExplosionResistance() < 600f) return false;
        }

        return true;
    }

    private boolean groundedResistant(BlockPos pos) {
        return state(pos).getBlock().getExplosionResistance() >= 600f && mc.player.onGround();
    }

    private boolean closedUpperTrapdoor(BlockPos pos) {
        BlockState s = state(pos);
        return s.getBlock() instanceof TrapDoorBlock && !s.getValue(TrapDoorBlock.OPEN) && s.getValue(TrapDoorBlock.HALF) != Half.BOTTOM;
    }

    private boolean lowProfile() {
        return mc.player.getBbHeight() < 1.0f;
    }

    private Player selectTarget() {
        Player best = null;
        float bestDiff = Float.MAX_VALUE;

        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator() || Friends.get().isFriend(p)) continue;
            if (p.distanceTo(mc.player) > targetRange.get()) continue;
            if (ignoreNaked.get() && p.getArmorValue() == 0) continue;

            float diff = Mth.degreesDifferenceAbs(mc.player.getYRot(), (float) Rotations.getYaw(p));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = p;
            }
        }

        return best;
    }

    private boolean valid(Player p) {
        return p != null && p.isAlive() && !p.isRemoved() && p.distanceTo(mc.player) <= targetRange.get() + 2;
    }

    private Set<BlockPos> cells(Player p) {
        return cellsOf(p);
    }

    private Set<BlockPos> cellsOf(Player p) {
        AABB bb = p.getBoundingBox();
        int y = Mth.floor(bb.minY + 1e-7);
        Set<BlockPos> out = new LinkedHashSet<>();

        for (int x = Mth.floor(bb.minX + 1e-7); x <= Mth.floor(bb.maxX - 1e-7); x++) {
            for (int z = Mth.floor(bb.minZ + 1e-7); z <= Mth.floor(bb.maxZ - 1e-7); z++) {
                out.add(new BlockPos(x, y, z));
            }
        }

        return out;
    }

    private Set<BlockPos> ring(Set<BlockPos> cells, boolean diagonals) {
        Set<BlockPos> out = new LinkedHashSet<>();

        for (BlockPos cell : cells) {
            for (Direction d : HORIZONTAL) {
                BlockPos n = cell.relative(d);
                if (!cells.contains(n)) out.add(n);
            }

            if (!diagonals) continue;

            for (int dx = -1; dx <= 1; dx += 2) {
                for (int dz = -1; dz <= 1; dz += 2) {
                    BlockPos n = cell.offset(dx, 0, dz);
                    if (!cells.contains(n)) out.add(n);
                }
            }
        }

        return out;
    }

    private boolean ignored(BlockState s) {
        return s.isAir() || s.canBeReplaced()
            || s.is(Blocks.LADDER) || s.is(Blocks.VINE) || s.is(Blocks.SCAFFOLDING)
            || s.is(Blocks.COBWEB) || s.is(Blocks.ANVIL);
    }

    private boolean canMine(BlockPos pos) {
        BlockState s = state(pos);
        if (s.isAir() || s.canBeReplaced() || s.is(Blocks.BEDROCK) || !BlockUtils.canBreak(pos)) return false;

        return PlayerUtils.isWithinReach(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private boolean mining(BlockPos pos) {
        return speedMine.isMiningBlock(pos);
    }

    private boolean air(BlockPos pos) {
        return state(pos).isAir();
    }

    private BlockState state(BlockPos pos) {
        return mc.level.getBlockState(pos);
    }

    private Direction direction(BlockPos pos) {
        return strictDirection.get() ? BlockUtils.getDirection(pos) : Direction.UP;
    }

    private void placeSupport(BlockPos pos) {
        if (!support.get() || !state(pos.below()).canBeReplaced()) return;

        FindItemResult obby = InvUtils.findInHotbar(Items.OBSIDIAN);
        if (obby.found()) BlockUtils.place(pos.below(), obby, rotate.get(), 0, true);
    }

    private void schedule(BlockPos pos, Direction dir, int delay) {
        if (delay <= 0) start(pos, dir);
        else pending.add(new Pending(pos, dir, ticks + delay));
    }

    private void runPending() {
        pending.removeIf(p -> {
            if (ticks < p.due()) return false;
            start(p.pos(), p.dir());
            return true;
        });
    }

    private void start(BlockPos pos, Direction dir) {
        if (air(pos)) return;

        Runnable add = () -> speedMine.addTarget(pos, dir);

        if (!rotate.get()) {
            add.run();
            return;
        }

        Vec3 point = raytrace.get() ? visiblePoint(pos) : null;
        if (point == null) point = Vec3.atCenterOf(pos);

        Rotations.rotate(Rotations.getYaw(point), Rotations.getPitch(point), 100, add);
    }

    private Vec3 visiblePoint(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        double[] offsets = {0.1, 0.5, 0.9};

        for (double x : offsets) {
            for (double y : offsets) {
                for (double z : offsets) {
                    Vec3 point = new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    HitResult hit = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));

                    if (hit instanceof BlockHitResult block && block.getBlockPos().equals(pos)) return point;
                }
            }
        }

        return null;
    }

    private void push() {
        AutoCrystal crystal = Modules.get().get(AutoCrystal.class);

        if (crystalSync.get() && crystal != null && crystal.isActive()) {
            crystal.onSurroundBroken(primaryPos, target);
            finishCycle();
            return;
        }

        boolean done = true;

        if (forcePush.get()) {
            if (pushMode.get() == PushMode.Shulker) pushShulker(primaryPos);
            else done = pushPiston();
        }

        if (done) finishCycle();
    }

    private void pushShulker(BlockPos brokenPos) {
        int shulkerSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (Block.byItem(mc.player.getInventory().getItem(i).getItem()) instanceof ShulkerBoxBlock) {
                shulkerSlot = i;
                break;
            }
        }

        if (shulkerSlot == -1) {
            if (chatInfo.get()) warning("No shulker box in hotbar.");
            return;
        }

        Direction pushDir = getPushDirection();
        BlockPos anchorPos = brokenPos.relative(pushDir.getOpposite());

        BlockHitResult placeHit = new BlockHitResult(
            Vec3.atCenterOf(anchorPos).add(Vec3.atLowerCornerOf(pushDir.getUnitVec3i()).scale(0.5)),
            pushDir, anchorPos, false
        );
        BlockHitResult openHit = new BlockHitResult(Vec3.atCenterOf(brokenPos), pushDir, brokenPos, false);

        int originalSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(shulkerSlot));
        mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, placeHit, 0));
        mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, openHit, 1));
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(originalSlot));

        if (chatInfo.get()) info("Pushed player with shulker.");
    }

    private boolean pushPiston() {
        Direction pushDir = getPushDirection();
        BlockPos oppositeFoot = target.blockPosition().relative(pushDir);

        boolean headLevel = !state(oppositeFoot).canBeReplaced();
        BlockPos pushTargetPos = headLevel ? primaryPos.above() : primaryPos;
        BlockPos pistonPos = pushTargetPos.relative(pushDir.getOpposite());

        if (pistonStage == 0) {
            BlockState s = state(pistonPos);
            if (s.is(Blocks.PISTON) || s.is(Blocks.STICKY_PISTON)) {
                pistonStage = 1;
                return false;
            }

            FindItemResult piston = InvUtils.findInHotbar(Items.PISTON, Items.STICKY_PISTON);
            if (!piston.found()) {
                if (chatInfo.get()) warning("No piston in hotbar.");
                return true;
            }

            Rotations.rotate(pushDir.getOpposite().toYRot(), 0, 50, () -> BlockUtils.place(pistonPos, piston, false, 0, true));
            pistonStage = 1;
            return false;
        }

        FindItemResult redstone = InvUtils.findInHotbar(Items.REDSTONE_BLOCK);
        if (!redstone.found()) {
            if (chatInfo.get()) warning("No redstone block in hotbar.");
            return true;
        }

        for (Direction side : Direction.values()) {
            if (side == pushDir) continue;
            BlockPos redstonePos = pistonPos.relative(side);

            if (state(redstonePos).canBeReplaced() && BlockUtils.place(redstonePos, redstone, rotate.get(), 0, true)) {
                if (chatInfo.get()) info("Pushed player out of hole with piston.");
                return true;
            }
        }

        return true;
    }

    private Direction getPushDirection() {
        double dx = target.getX() - primaryPos.getX();
        double dz = target.getZ() - primaryPos.getZ();

        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void finishCycle() {
        drop();
        cooldown = cooldownTicks.get();
        if (autoDisable.get()) toggle();
    }

    private void release() {
        pending.clear();

        if (primaryPos != null) speedMine.removeTarget(primaryPos);
        if (secondaryPos != null) speedMine.removeTarget(secondaryPos);

        primaryPos = secondaryPos = null;
        primaryKind = null;
        pistonStage = 0;
    }

    private void drop() {
        release();
        target = null;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderBlock.get()) return;

        if (primaryPos != null) event.renderer.box(primaryPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        if (secondaryPos != null) event.renderer.box(secondaryPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    @Override
    public String getInfoString() {
        if (target != null) return EntityUtils.getName(target);
        return primaryKind == null ? null : primaryKind.name();
    }
}
