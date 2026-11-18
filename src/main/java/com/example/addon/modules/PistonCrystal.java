package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

public class PistonCrystal extends Module {
    public enum Power { Lever, RedstoneBlock }
    private enum Stage { Place, Wait, Reset }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range").description("Radius to scan for targets.")
        .defaultValue(8.0).min(1.0).sliderMax(12.0).build());

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range").description("Maximum placement reach.")
        .defaultValue(5.0).min(1.0).sliderMax(6.0).build());

    private final Setting<Double> attackRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("attack-range").description("Maximum crystal attack reach.")
        .defaultValue(5.0).min(1.0).sliderMax(6.0).build());

    private final Setting<Power> power = sgGeneral.add(new EnumSetting.Builder<Power>()
        .name("power").description("What powers the piston.")
        .defaultValue(Power.Lever).build());

    private final Setting<Boolean> fallback = sgGeneral.add(new BoolSetting.Builder()
        .name("fallback").description("Uses the other power item if the chosen one is missing.")
        .defaultValue(true).build());

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place").description("Places pistons and redstone blocks with no neighboring block.")
        .defaultValue(true).build());

    private final Setting<Boolean> sameTick = sgGeneral.add(new BoolSetting.Builder()
        .name("same-tick").description("Places piston, crystal and power in one tick.")
        .defaultValue(true).build());

    private final Setting<Integer> forceDetonate = sgGeneral.add(new IntSetting.Builder()
        .name("force-detonate").description("Ticks after powering before an unpushed crystal is detonated in place. 0 disables.")
        .defaultValue(8).min(0).sliderMax(20).build());

    private final Setting<Boolean> selfSafety = sgGeneral.add(new BoolSetting.Builder()
        .name("self-safety").description("Skips setups and detonations that would drop you below min-health.")
        .defaultValue(false).build());

    private final Setting<Double> minHealth = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-health").description("Health you must keep after the estimated blast.")
        .defaultValue(8.0).min(1.0).sliderMax(20.0).visible(selfSafety::get).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Ticks between cycles.")
        .defaultValue(2).min(0).sliderMax(10).build());

    private final Setting<Integer> swapSlot = sgGeneral.add(new IntSetting.Builder()
        .name("swap-slot").description("Hotbar slot (1-9) for items pulled from the inventory.")
        .defaultValue(9).min(1).max(9).build());

    private final Setting<Boolean> placeSupport = sgGeneral.add(new BoolSetting.Builder()
        .name("place-support").description("Places obsidian under the crystal if missing.")
        .defaultValue(true).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Rotates toward blocks and crystals. Pistons always rotate.")
        .defaultValue(true).build());

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Turns off after one cycle.")
        .defaultValue(false).build());

    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-info").description("Warns about missing items and blocked setups.")
        .defaultValue(true).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Renders the planned positions.")
        .defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How shapes are rendered.")
        .defaultValue(ShapeMode.Both).visible(render::get).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Side color.")
        .defaultValue(new SettingColor(255, 80, 0, 60)).visible(render::get).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Line color.")
        .defaultValue(new SettingColor(255, 80, 0, 255)).visible(render::get).build());

    private volatile Stage stage = Stage.Place;
    private Player target;
    private Direction outward, inward;
    private BlockPos anchor, basePos, crystalPos, pistonPos, powerPos, breakPos;
    private boolean planLever, breakStarted;
    private int ticks, cooldown, stuck, waitTicks, breakTicks;
    private int pistonSent = -100, crystalSent = -100, toggleTick = -100;
    private double breakProgress;

    public PistonCrystal() {
        super(AddonTemplate.CATEGORY, "piston-crystal", "Pushes a crystal into the target with a lever or redstone block powered piston.");
    }

    @Override
    public void onActivate() {
        clear();
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    private void clear() {
        stage = Stage.Place;
        target = null;
        outward = inward = null;
        anchor = basePos = crystalPos = pistonPos = powerPos = breakPos = null;
        planLever = false;
        cooldown = stuck = waitTicks = 0;
        pistonSent = crystalSent = toggleTick = -100;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        ticks++;

        if (tickBreak()) return;
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (target == null || !target.isAlive() || target.isRemoved() || target.distanceTo(mc.player) > targetRange.get()) {
            clear();
            target = findTarget();
            if (target == null) return;
        }

        if (crystalPos != null && stage == Stage.Place && !target.blockPosition().equals(anchor)) crystalPos = null;

        if (crystalPos == null && !plan()) {
            if (chatInfo.get() && ticks % 40 == 0) warning("No valid setup around target (range, blocked spots, or no lever support).");
            return;
        }

        switch (stage) {
            case Place -> place();
            case Wait -> waitForPush();
            case Reset -> reset();
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (stage != Stage.Wait) return;
        if (event.packet instanceof ClientboundMoveEntityPacket || event.packet instanceof ClientboundTeleportEntityPacket) {
            mc.execute(this::detonate);
        }
    }

    private Player findTarget() {
        Player best = null;
        double bestDist = targetRange.get();

        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator() || Friends.get().isFriend(p)) continue;
            double d = p.distanceTo(mc.player);
            if (d <= bestDist) {
                bestDist = d;
                best = p;
            }
        }

        return best;
    }

    private boolean plan() {
        Vec3 eye = mc.player.getEyePosition();
        BlockPos feet = target.blockPosition();
        double best = Double.MAX_VALUE;
        boolean found = false;

        Item planned = powerItem();
        if (planned == null) planned = Items.REDSTONE_BLOCK;

        for (Direction out : Direction.Plane.HORIZONTAL) {
            Direction in = out.getOpposite();
            BlockPos push = feet.relative(out).above();
            BlockPos crystal = feet.relative(out, 2).above();
            BlockPos base = crystal.below();
            BlockPos piston = feet.relative(out, 3).above();

            if (!state(push).canBeReplaced() || !state(crystal).canBeReplaced()) continue;
            if (!isBase(state(base)) && !(placeSupport.get() && state(base).canBeReplaced())) continue;

            BlockState ps = state(piston);
            if (!isPiston(ps, in) && !ps.canBeReplaced()) continue;

            if (eye.distanceTo(Vec3.atCenterOf(piston)) > placeRange.get()) continue;
            if (eye.distanceTo(Vec3.atCenterOf(crystal)) > placeRange.get()) continue;
            if (selfSafety.get() && !safe(Vec3.atCenterOf(push))) continue;

            boolean lever = planned == Items.LEVER;
            BlockPos pw = findPowerSpot(piston, in, eye, lever);
            if (pw == null && lever && has(Items.REDSTONE_BLOCK)) {
                lever = false;
                pw = findPowerSpot(piston, in, eye, false);
            }
            if (pw == null) continue;

            double d = eye.distanceToSqr(Vec3.atCenterOf(crystal));
            if (d < best) {
                best = d;
                found = true;
                outward = out;
                inward = in;
                basePos = base;
                crystalPos = crystal;
                pistonPos = piston;
                powerPos = pw;
                planLever = lever;
            }
        }

        if (!found) return false;
        anchor = feet;
        stage = Stage.Place;
        return true;
    }

    private BlockPos findPowerSpot(BlockPos piston, Direction in, Vec3 eye, boolean lever) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction d : Direction.values()) {
            if (d == in) continue;
            BlockPos p = piston.relative(d);
            BlockState s = state(p);
            boolean existing = s.is(Blocks.LEVER) || s.is(Blocks.REDSTONE_BLOCK);
            if (!existing && !s.canBeReplaced()) continue;

            double dist = eye.distanceTo(Vec3.atCenterOf(p));
            if (dist > placeRange.get() || dist >= bestDist) continue;
            if (!existing && lever && findHit(p, sup -> !sup.equals(piston), false) == null) continue;

            best = p;
            bestDist = dist;
        }

        return best;
    }

    private void place() {
        if (++stuck > 30) {
            clear();
            return;
        }

        BlockState ps = state(pistonPos);
        if ((ps.is(Blocks.PISTON) || ps.is(Blocks.STICKY_PISTON)) && !isPiston(ps, inward)) {
            startBreak(pistonPos);
            return;
        }

        boolean fast = sameTick.get();

        if (!isBase(state(basePos))) {
            if (!placeSupport.get() || !placeObsidian()) {
                warn("obsidian");
                return;
            }
            if (!fast) return;
        }

        if (!isPiston(ps, inward) && ticks - pistonSent >= 3) {
            if (!placePiston()) {
                warn("piston");
                return;
            }
            pistonSent = ticks;
            if (!fast) return;
        }

        if (crystalAt(crystalPos) == null && ticks - crystalSent >= 3) {
            if (!state(crystalPos).canBeReplaced()) return;
            if (!placeCrystal()) {
                warn("end crystal");
                return;
            }
            crystalSent = ticks;
            if (!fast) return;
        }

        if (crystalAt(crystalPos) == null && ticks - crystalSent >= 3) return;

        powerUp();
    }

    private void powerUp() {
        BlockState s = state(powerPos);

        if (s.is(Blocks.LEVER)) {
            if (!s.getValue(BlockStateProperties.POWERED) && ticks - toggleTick >= 6) toggle(powerPos);
        } else if (!s.is(Blocks.REDSTONE_BLOCK)) {
            if (!placePower()) {
                warn(planLever ? "lever" : "redstone block");
                return;
            }
        }

        stage = Stage.Wait;
        waitTicks = 0;
        stuck = 0;
    }

    private void waitForPush() {
        waitTicks++;

        BlockState s = state(powerPos);
        if (s.is(Blocks.LEVER) && !s.getValue(BlockStateProperties.POWERED) && ticks - toggleTick >= 6) toggle(powerPos);

        detonate();
        if (stage != Stage.Wait) return;

        int force = forceDetonate.get();
        if (force > 0 && waitTicks >= force) {
            EndCrystal crystal = crystalAt(crystalPos);
            if (crystal != null) attack(crystal);
            stage = Stage.Reset;
        } else if (waitTicks > 20) {
            stage = Stage.Reset;
        }
    }

    private void detonate() {
        if (stage != Stage.Wait || mc.player == null || mc.level == null || target == null) return;

        EndCrystal crystal = pushedCrystal();
        if (crystal == null) return;

        stage = Stage.Reset;
        attack(crystal);
    }

    private void attack(EndCrystal crystal) {
        if (selfSafety.get() && !safe(crystal.position())) return;
        if (mc.player.getEyePosition().distanceTo(crystal.position()) > attackRange.get()) return;

        Runnable action = () -> {
            mc.gameMode.attack(mc.player, crystal);
            mc.player.swing(InteractionHand.MAIN_HAND);
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(crystal), Rotations.getPitch(crystal), 50, action);
        else action.run();
    }

    private void reset() {
        BlockState s = state(powerPos);

        if (s.is(Blocks.REDSTONE_BLOCK)) {
            startBreak(powerPos);
            return;
        }

        if (s.is(Blocks.LEVER) && s.getValue(BlockStateProperties.POWERED)) {
            if (ticks - toggleTick >= 6) toggle(powerPos);
            return;
        }

        stage = Stage.Place;
        pistonSent = crystalSent = -100;
        stuck = waitTicks = 0;
        cooldown = delay.get();

        if (autoDisable.get()) toggle();
    }

    private boolean placeObsidian() {
        int slot = hotbarSlot(Items.OBSIDIAN);
        BlockHitResult hit = findHit(basePos, sup -> true, true);
        if (slot == -1 || hit == null) return false;

        then(basePos, () -> use(slot, hit));
        return true;
    }

    private boolean placePiston() {
        Item item = has(Items.PISTON) ? Items.PISTON : has(Items.STICKY_PISTON) ? Items.STICKY_PISTON : null;
        if (item == null) return false;

        int slot = hotbarSlot(item);
        BlockHitResult hit = findHit(pistonPos, sup -> true, true);
        if (slot == -1 || hit == null) return false;

        Rotations.rotate(outward.toYRot(), 0, 50, () -> use(slot, hit));
        return true;
    }

    private boolean placeCrystal() {
        int slot = hotbarSlot(Items.END_CRYSTAL);
        if (slot == -1) return false;

        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(basePos).add(0, 0.5, 0), Direction.UP, basePos, false);
        then(basePos, () -> use(slot, hit));
        return true;
    }

    private boolean placePower() {
        Item item = planLever ? Items.LEVER : Items.REDSTONE_BLOCK;
        if (!has(item)) return false;

        int slot = hotbarSlot(item);
        BlockHitResult hit = planLever
            ? findHit(powerPos, sup -> !sup.equals(pistonPos), false)
            : findHit(powerPos, sup -> true, true);
        if (slot == -1 || hit == null) return false;

        then(powerPos, () -> {
            use(slot, hit);
            if (planLever) toggle(powerPos);
        });
        return true;
    }

    private Item powerItem() {
        Item main = power.get() == Power.Lever ? Items.LEVER : Items.REDSTONE_BLOCK;
        Item alt = main == Items.LEVER ? Items.REDSTONE_BLOCK : Items.LEVER;

        if (has(main)) return main;
        return fallback.get() && has(alt) ? alt : null;
    }

    private void toggle(BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
        mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        toggleTick = ticks;
    }

    private void use(int slot, BlockHitResult hit) {
        int prev = mc.player.getInventory().getSelectedSlot();
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
        mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(prev));
    }

    private void then(BlockPos at, Runnable action) {
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(at), Rotations.getPitch(at), 50, action);
        else action.run();
    }

    private void startBreak(BlockPos pos) {
        if (pos.equals(breakPos)) return;
        breakPos = pos;
        breakProgress = 0;
        breakTicks = 0;
        breakStarted = false;
    }

    private boolean tickBreak() {
        if (breakPos == null) return false;

        BlockState s = state(breakPos);
        if (s.isAir() || s.canBeReplaced() || ++breakTicks > 40) {
            breakPos = null;
            return false;
        }

        FindItemResult tool = InvUtils.findFastestTool(s);
        int prev = mc.player.getInventory().getSelectedSlot();
        int slot = tool.found() && tool.isHotbar() ? tool.slot() : prev;

        if (!breakStarted) {
            mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, breakPos, Direction.UP));
            mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            breakStarted = true;
        }

        breakProgress += BlockUtils.getBreakDelta(slot, s);

        if (breakProgress >= 0.7) {
            if (slot != prev) mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, breakPos, Direction.UP));
            if (slot != prev) mc.player.connection.send(new ServerboundSetCarriedItemPacket(prev));
        }

        return true;
    }

    private BlockHitResult findHit(BlockPos pos, Predicate<BlockPos> allowed, boolean air) {
        Vec3 eye = mc.player.getEyePosition();

        for (Direction d : Direction.values()) {
            BlockPos support = pos.relative(d);
            BlockState s = state(support);
            if (s.isAir() || s.canBeReplaced() || !allowed.test(support)) continue;

            Vec3 point = Vec3.atCenterOf(support).relative(d.getOpposite(), 0.5);
            if (eye.distanceTo(point) > placeRange.get()) continue;

            return new BlockHitResult(point, d.getOpposite(), support, false);
        }

        if (air && airPlace.get() && eye.distanceTo(Vec3.atCenterOf(pos)) <= placeRange.get()) {
            return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        }

        return null;
    }

    private int hotbarSlot(Item item) {
        FindItemResult hotbar = InvUtils.findInHotbar(item);
        if (hotbar.found()) return hotbar.slot();

        FindItemResult inv = InvUtils.find(item);
        if (!inv.found()) return -1;

        int dest = swapSlot.get() - 1;
        InvUtils.move().from(inv.slot()).toHotbar(dest);
        return dest;
    }

    private boolean has(Item item) {
        return InvUtils.find(item).found();
    }

    private EndCrystal pushedCrystal() {
        EndCrystal best = null;
        double bestDist = 9;

        for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, target.getBoundingBox().inflate(3))) {
            if (c.blockPosition().equals(crystalPos)) continue;
            double d = c.distanceToSqr(target);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }

        return best;
    }

    private EndCrystal crystalAt(BlockPos pos) {
        return mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(pos)).stream().findFirst().orElse(null);
    }

    private boolean safe(Vec3 blast) {
        double dist = mc.player.position().distanceTo(blast);
        if (dist >= 12) return true;

        double impact = 1 - dist / 12;
        double dmg = (impact * impact + impact) / 2 * 84 + 1;

        double armor = mc.player.getArmorValue();
        double toughness = mc.player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        dmg *= 1 - Math.min(20, Math.max(armor / 5, armor - dmg / (2 + toughness / 4))) / 25;

        return mc.player.getHealth() + mc.player.getAbsorptionAmount() - dmg >= minHealth.get();
    }

    private boolean isPiston(BlockState s, Direction facing) {
        return (s.is(Blocks.PISTON) || s.is(Blocks.STICKY_PISTON)) && s.getValue(DirectionalBlock.FACING) == facing;
    }

    private boolean isBase(BlockState s) {
        return s.is(Blocks.OBSIDIAN) || s.is(Blocks.BEDROCK);
    }

    private BlockState state(BlockPos pos) {
        return mc.level.getBlockState(pos);
    }

    private void warn(String missing) {
        if (chatInfo.get() && ticks % 40 == 0) warning("Missing or unplaceable: " + missing + ".");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || target == null || crystalPos == null) return;

        event.renderer.box(crystalPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        event.renderer.box(pistonPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        event.renderer.box(powerPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    @Override
    public String getInfoString() {
        return target == null ? null : EntityUtils.getName(target);
    }
}
