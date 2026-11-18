package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Blocker extends Module {
    public enum Mode { DAMAGE, INSTANT, DESTROY }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgTargets = settings.createGroup("Targets");

    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("DAMAGE: react to break progress. DESTROY: react once the block is gone. INSTANT: always pre-block.")
        .defaultValue(Mode.DAMAGE).build());
    private final Setting<Double> minDamage = sg.add(new DoubleSetting.Builder()
        .name("block-damage").description("Break progress that triggers blocking.")
        .defaultValue(0.8).range(0.1, 1.0).sliderRange(0.1, 1.0)
        .visible(() -> mode.get() == Mode.DAMAGE).build());
    private final Setting<List<Block>> blocks = sg.add(new BlockListSetting.Builder()
        .name("blocks").description("Blocks to place with.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.ENDER_CHEST).build());
    private final Setting<Integer> perTick = sg.add(new IntSetting.Builder()
        .name("blocks-per-tick").defaultValue(2).min(1).sliderMax(8).build());
    private final Setting<Integer> delay = sg.add(new IntSetting.Builder()
        .name("delay").description("Ticks to wait between placement rounds.")
        .defaultValue(0).min(0).sliderMax(10).build());
    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("rotate").defaultValue(false).build());

    private final Setting<Boolean> surround = sgTargets.add(new BoolSetting.Builder()
        .name("surround").description("Watch the blocks beside you.").defaultValue(true).build());
    private final Setting<Boolean> corners = sgTargets.add(new BoolSetting.Builder()
        .name("corners").description("Block all sides of a triggered position, not just the outer one.")
        .defaultValue(false).visible(surround::get).build());
    private final Setting<Boolean> face = sgTargets.add(new BoolSetting.Builder()
        .name("face").description("Also block above a triggered position.")
        .defaultValue(true).visible(surround::get).build());
    private final Setting<Boolean> cev = sgTargets.add(new BoolSetting.Builder()
        .name("cev").description("Watch the blocks 2 and 3 above you.").defaultValue(true).build());
    private final Setting<Boolean> extended = sgTargets.add(new BoolSetting.Builder()
        .name("extended").description("Also watch the blocks above your surround.")
        .defaultValue(false).visible(cev::get).build());
    private final Setting<Boolean> fish = sgTargets.add(new BoolSetting.Builder()
        .name("fish").description("Protect the floor under you too.").defaultValue(false).build());

    private record Damage(float value, long time) {}

    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final long DESTROY_MS = 150;
    private static final long STALE_MS = 5000;
    private static final long OWN_MINING_MS = 10000;

    // Packets arrive on the network thread, so these must be thread-safe.
    private final Map<BlockPos, Damage> progress = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> destroyed = new ConcurrentHashMap<>();

    private volatile BlockPos ownMining;
    private volatile long ownMiningTime;
    private int timer;

    public Blocker() {
        super(AddonTemplate.CATEGORY, "blocker", "Blocks offsets to prevent crystal damage.");
    }

    @Override
    public String getInfoString() {
        return mode.get() == Mode.DAMAGE ? String.format("%.1f", minDamage.get()) : mode.get().name();
    }

    @Override
    public void onActivate() {
        progress.clear();
        destroyed.clear();
        ownMining = null;
        timer = 0;
    }

    private boolean isOwnMining(BlockPos pos) {
        BlockPos own = ownMining;
        return own != null && own.equals(pos) && System.currentTimeMillis() - ownMiningTime < OWN_MINING_MS;
    }

    private void markDestroyed(BlockPos pos) {
        if (isOwnMining(pos)) return;
        destroyed.put(pos.immutable(), System.currentTimeMillis() + DESTROY_MS);
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof ServerboundPlayerActionPacket p)) return;

        switch (p.getAction()) {
            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                ownMining = p.getPos().immutable();
                ownMiningTime = System.currentTimeMillis();
            }
            case ABORT_DESTROY_BLOCK -> {
                if (p.getPos().equals(ownMining)) ownMining = null;
            }
            default -> {}
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;

        if (event.packet instanceof ClientboundBlockDestructionPacket p) {
            if (p.getId() == mc.player.getId()) return; // our own mining
            int stage = p.getProgress();
            if (stage < 0 || stage > 9) progress.remove(p.getPos());
            else progress.put(p.getPos().immutable(), new Damage((stage + 1) / 10f, System.currentTimeMillis()));
        } else if (event.packet instanceof ClientboundBlockUpdatePacket p) {
            if (p.getBlockState().isAir()) markDestroyed(p.getPos());
        } else if (event.packet instanceof ClientboundSectionBlocksUpdatePacket p) {
            p.runUpdates((pos, state) -> {
                if (state.isAir()) markDestroyed(pos);
            });
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        long now = System.currentTimeMillis();
        destroyed.values().removeIf(expiry -> now > expiry);
        progress.values().removeIf(d -> now - d.time() > STALE_MS);

        if (timer > 0) {
            timer--;
            return;
        }

        FindItemResult item = InvUtils.findInHotbar(stack ->
            stack.getItem() instanceof BlockItem bi && blocks.get().contains(bi.getBlock()));
        if (!item.found()) return;

        int placed = 0;
        for (BlockPos pos : getPlacements()) {
            if (placed >= perTick.get()) break;
            if (!BlockUtils.canPlace(pos)) continue;
            if (BlockUtils.place(pos, item, rotate.get(), 50)) placed++;
        }

        if (placed > 0) timer = delay.get();
    }

    private boolean triggered(BlockPos pos) {
        switch (mode.get()) {
            case DAMAGE: {
                Damage d = progress.get(pos);
                return d != null && d.value() >= minDamage.get();
            }
            case INSTANT:
                // Anything that isn't unbreakable (bedrock and similar have negative hardness).
                return mc.level.getBlockState(pos).getDestroySpeed(mc.level, pos) >= 0;
            case DESTROY: {
                Long expiry = destroyed.get(pos);
                return expiry != null && System.currentTimeMillis() <= expiry;
            }
            default:
                return false;
        }
    }

    // Every block column the player's hitbox overlaps, at feet height.
    private Set<BlockPos> occupied() {
        AABB box = mc.player.getBoundingBox();
        int y = mc.player.blockPosition().getY();
        int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(box.maxX - 1e-5);
        int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(box.maxZ - 1e-5);

        Set<BlockPos> out = new LinkedHashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) out.add(new BlockPos(x, y, z));
        }
        return out;
    }

    private Set<BlockPos> surroundPositions(Set<BlockPos> occupied) {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (BlockPos o : occupied) {
            for (Direction d : SIDES) {
                BlockPos n = o.relative(d);
                if (!occupied.contains(n)) out.add(n);
            }
        }
        return out;
    }

    private List<BlockPos> targets(Set<BlockPos> occupied, Set<BlockPos> surroundPos) {
        List<BlockPos> out = new ArrayList<>();
        if (surround.get()) out.addAll(surroundPos);
        if (cev.get()) {
            for (BlockPos o : occupied) {
                out.add(o.above(2));
                out.add(o.above(3));
            }
            if (extended.get()) for (BlockPos s : surroundPos) out.add(s.above());
        }
        return out;
    }

    private Set<BlockPos> getPlacements() {
        Set<BlockPos> occupied = occupied();
        Set<BlockPos> surroundPos = surroundPositions(occupied);
        int feetY = mc.player.blockPosition().getY();
        Set<BlockPos> out = new LinkedHashSet<>();

        // Fish: protect the floor when standing on a single block.
        if (fish.get() && occupied.size() == 1) {
            BlockPos feet = occupied.iterator().next();
            if (triggered(feet.below())) {
                for (BlockPos s : surroundPos) out.add(s.below());
            }
        }

        for (BlockPos target : targets(occupied, surroundPos)) {
            if (!triggered(target)) continue;

            if (target.getY() == feetY) {
                if (corners.get()) {
                    for (Direction d : SIDES) {
                        BlockPos side = target.relative(d);
                        if (!occupied.contains(side)) out.add(side);
                    }
                } else {
                    // Only the block directly outward from the part of you it touches.
                    for (BlockPos o : occupied) {
                        for (Direction d : SIDES) {
                            if (o.relative(d).equals(target)) out.add(target.relative(d));
                        }
                    }
                }
            }

            if (target.getY() == feetY + 3) out.add(target.below());
            else if (face.get()) out.add(target.above());
        }
        return out;
    }
}
