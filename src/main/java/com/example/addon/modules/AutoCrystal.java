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
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AutoCrystal extends Module {
    public enum MineSync { Off, Priority, Only }

    private enum Lethality { None, Pop, Kill }

    private record Eval(LivingEntity target, double damage, Lethality lethality) {
        boolean better(Eval other) {
            return other == null || lethality.ordinal() > other.lethality.ordinal()
                || (lethality == other.lethality && damage > other.damage);
        }
    }

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlace = settings.createGroup("Place");
    private final SettingGroup sgBreak = settings.createGroup("Break");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range").description("Radius to scan for targets.")
        .defaultValue(10.0).min(1.0).sliderMax(15.0).build());

    private final Setting<Integer> maxTargets = sgGeneral.add(new IntSetting.Builder()
        .name("max-targets").description("Closest players scored for every spot. Lower it if FPS drops.")
        .defaultValue(3).range(1, 6).sliderRange(1, 6).build());

    private final Setting<Boolean> pauseEating = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-eating").description("Pauses while using an item.")
        .defaultValue(true).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Rotates toward placements and crystals.")
        .defaultValue(false).build());

    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-suicide").description("Never acts if the blast would kill you, unless it kills and you hold a totem.")
        .defaultValue(true).build());

    private final Setting<Boolean> antiFriendKill = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-friend-kill").description("Rejects any crystal that would kill a friend.")
        .defaultValue(true).build());

    private final Setting<MineSync> mineSync = sgGeneral.add(new EnumSetting.Builder<MineSync>()
        .name("mine-sync").description("Places at the surround block AutoMine just broke. Only skips normal placements.")
        .defaultValue(MineSync.Priority).build());

    private final Setting<Double> mineMinDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("mine-min-damage").description("Minimum damage for a crystal placed in a mined surround spot.")
        .defaultValue(2.0).min(0).sliderMax(10).visible(() -> mineSync.get() != MineSync.Off).build());

    private final Setting<Boolean> mineIgnore = sgGeneral.add(new BoolSetting.Builder()
        .name("mine-ignore").description("Skips crystal bases SpeedMinev1 is currently mining.")
        .defaultValue(true).build());

    private final Setting<Boolean> place = sgPlace.add(new BoolSetting.Builder()
        .name("place").description("Places crystals.").defaultValue(true).build());

    private final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("place-delay").description("Ticks between placements.")
        .defaultValue(0).min(0).sliderMax(10).visible(place::get).build());

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range").description("Maximum placement reach.")
        .defaultValue(4.5).min(1.0).sliderMax(6.0).visible(place::get).build());

    private final Setting<Double> minPlaceDamage = sgPlace.add(new DoubleSetting.Builder()
        .name("min-place-damage").description("Minimum damage to a target. Capped at the target's health.")
        .defaultValue(4.0).min(0).sliderMax(20).visible(place::get).build());

    private final Setting<Double> maxPlaceSelf = sgPlace.add(new DoubleSetting.Builder()
        .name("max-place-self").description("Maximum damage to you.")
        .defaultValue(6.0).min(0).sliderMax(20).visible(place::get).build());

    private final Setting<Double> damageRatio = sgPlace.add(new DoubleSetting.Builder()
        .name("damage-ratio").description("Minimum target damage divided by self damage for placements.")
        .defaultValue(1.0).min(0).sliderMax(5).visible(place::get).build());

    private final Setting<Boolean> facePlace = sgPlace.add(new BoolSetting.Builder()
        .name("face-place").description("Drops minimum damage to 0.6 on low health, weak armor or portals.")
        .defaultValue(true).build());

    private final Setting<Double> facePlaceHealth = sgPlace.add(new DoubleSetting.Builder()
        .name("face-place-health").description("Target health that triggers face place.")
        .defaultValue(8.0).min(1).sliderMax(36).visible(facePlace::get).build());

    private final Setting<Integer> facePlaceArmor = sgPlace.add(new IntSetting.Builder()
        .name("face-place-armor").description("Armor durability percent that triggers face place.")
        .defaultValue(20).range(0, 100).sliderRange(0, 100).visible(facePlace::get).build());

    private final Setting<Boolean> breakCrystals = sgBreak.add(new BoolSetting.Builder()
        .name("break").description("Breaks crystals.").defaultValue(true).build());

    private final Setting<Integer> breakDelay = sgBreak.add(new IntSetting.Builder()
        .name("break-delay").description("Ticks between attacks.")
        .defaultValue(0).min(0).sliderMax(10).visible(breakCrystals::get).build());

    private final Setting<Double> breakRange = sgBreak.add(new DoubleSetting.Builder()
        .name("break-range").description("Maximum attack reach.")
        .defaultValue(4.5).min(1.0).sliderMax(6.0).visible(breakCrystals::get).build());

    private final Setting<Double> minBreakDamage = sgBreak.add(new DoubleSetting.Builder()
        .name("min-break-damage").description("Minimum damage to a target. Capped at the target's health.")
        .defaultValue(2.0).min(0).sliderMax(20).visible(breakCrystals::get).build());

    private final Setting<Double> maxBreakSelf = sgBreak.add(new DoubleSetting.Builder()
        .name("max-break-self").description("Maximum damage to you.")
        .defaultValue(8.0).min(0).sliderMax(20).visible(breakCrystals::get).build());

    private final Setting<Boolean> instant = sgBreak.add(new BoolSetting.Builder()
        .name("instant").description("Attacks a crystal the moment its spawn packet arrives.")
        .defaultValue(true).visible(breakCrystals::get).build());

    private final Setting<Integer> ticksExisted = sgBreak.add(new IntSetting.Builder()
        .name("ticks-existed").description("Crystal age in ticks before it can be broken.")
        .defaultValue(0).min(0).sliderMax(5).visible(breakCrystals::get).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Renders the last placement.").defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How shapes are rendered.")
        .defaultValue(ShapeMode.Both).visible(render::get).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Side color.")
        .defaultValue(new SettingColor(255, 0, 255, 40)).visible(render::get).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Line color.")
        .defaultValue(new SettingColor(255, 0, 255, 255)).visible(render::get).build());

    private final Set<Integer> attacked = new HashSet<>();
    private final List<LivingEntity> targets = new ArrayList<>();
    private final List<Player> friends = new ArrayList<>();
    private LivingEntity forcedTarget;
    private BlockPos forcedPos, renderPos;
    private int ticks, lastPlace, lastBreak, forcedTick, lastForced, renderTick;

    public AutoCrystal() {
        super(AddonTemplate.CATEGORY, "auto-crystal", "Places and breaks crystals with kill, pop and friend awareness, and fires the moment AutoMine breaks a surround block.");
    }

    @Override
    public void onActivate() {
        attacked.clear();
        targets.clear();
        friends.clear();
        forcedTarget = null;
        forcedPos = renderPos = null;
        ticks = lastPlace = lastBreak = forcedTick = lastForced = renderTick = 0;
    }

    public void onSurroundBroken(BlockPos pos, Player player) {
        if (mc.player == null || mc.level == null || mineSync.get() == MineSync.Off) return;
        if (pos.equals(forcedPos) && ticks - forcedTick < 5) return;

        refreshTargets();
        forcedPos = pos.immutable();
        forcedTarget = player;
        forcedTick = ticks;
        handleForced();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        ticks++;
        attacked.clear();

        if (pauseEating.get() && mc.player.isUsingItem()) return;

        refreshTargets();

        if (forcedPos != null) handleForced();
        if (targets.isEmpty()) return;

        if (breakCrystals.get() && ticks - lastBreak >= breakDelay.get()) breakBest();
        if (place.get() && mineSync.get() != MineSync.Only && ticks - lastPlace >= placeDelay.get()) placeBest();
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;
        Packet<?> packet = event.packet;

        if (packet instanceof ClientboundAddEntityPacket spawn && instant.get()) {
            int id = spawn.getId();
            later(() -> instantBreak(id));
        } else if (packet instanceof ClientboundBlockUpdatePacket update && mineSync.get() != MineSync.Off) {
            BlockPos pos = update.getPos().immutable();
            if (!update.getBlockState().isAir()) return;

            later(() -> {
                AutoMine autoMine = Modules.get().get(AutoMine.class);
                if (autoMine != null && autoMine.isActive() && pos.equals(autoMine.getMinedPos())) {
                    onSurroundBroken(pos, autoMine.getMineTarget());
                }
            });
        }
    }

    private void later(Runnable runnable) {
        mc.execute(() -> mc.execute(() -> {
            if (isActive() && mc.player != null && mc.level != null) runnable.run();
        }));
    }

    private void refreshTargets() {
        targets.clear();
        friends.clear();

        List<Player> candidates = new ArrayList<>();

        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator()) continue;

            double dist = p.distanceTo(mc.player);
            if (Friends.get().isFriend(p)) {
                if (dist <= 20) friends.add(p);
            } else if (dist <= targetRange.get()) {
                candidates.add(p);
            }
        }

        candidates.sort(Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)));

        if (mineSync.get() != MineSync.Off) {
            AutoMine autoMine = Modules.get().get(AutoMine.class);
            Player mined = autoMine != null && autoMine.isActive() ? autoMine.getMineTarget() : null;
            if (mined != null && candidates.remove(mined)) targets.add(mined);
        }

        for (Player p : candidates) {
            if (targets.size() >= maxTargets.get()) break;
            targets.add(p);
        }
    }

    private void handleForced() {
        if (ticks - forcedTick > 10) {
            forcedPos = null;
            return;
        }

        if (crystalAt(forcedPos) != null) {
            forcedPos = null;
            return;
        }

        BlockPos base = forcedPos.below();
        if (ticks - lastForced < 2 || !isBase(base) || !canPlace(base)) return;

        LivingEntity only = forcedTarget != null && forcedTarget.isAlive() ? forcedTarget : null;
        if (evaluate(crystalCenter(base), true, mineMinDamage.get(), only) == null) return;

        if (placeCrystal(base)) lastForced = ticks;
    }

    private void placeBest() {
        Vec3 eye = mc.player.getEyePosition();
        BlockPos origin = BlockPos.containing(eye);
        int r = (int) Math.ceil(placeRange.get());

        BlockPos best = null;
        Eval bestEval = null;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos base = origin.offset(x, y, z);
                    if (!isBase(base) || eye.distanceTo(Vec3.atCenterOf(base)) > placeRange.get()) continue;

                    Vec3 center = crystalCenter(base);
                    if (!nearTarget(center) || !canPlace(base)) continue;

                    Eval eval = evaluate(center, true, Double.NaN, null);
                    if (eval != null && eval.better(bestEval)) {
                        best = base;
                        bestEval = eval;
                    }
                }
            }
        }

        if (best != null && placeCrystal(best)) lastPlace = ticks;
    }

    private void breakBest() {
        EndCrystal best = null;
        Eval bestEval = null;

        for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, mc.player.getBoundingBox().inflate(breakRange.get() + 1))) {
            Eval eval = breakEval(c);
            if (eval != null && eval.better(bestEval)) {
                best = c;
                bestEval = eval;
            }
        }

        if (best != null) attack(best);
    }

    private void instantBreak(int id) {
        if (!breakCrystals.get() || targets.isEmpty() || !(mc.level.getEntity(id) instanceof EndCrystal crystal)) return;
        if (pauseEating.get() && mc.player.isUsingItem()) return;

        if (breakEval(crystal) != null) attack(crystal);
    }

    private Eval breakEval(EndCrystal c) {
        if (!c.isAlive() || attacked.contains(c.getId()) || c.tickCount < ticksExisted.get()) return null;
        if (mc.player.getEyePosition().distanceTo(c.position()) > breakRange.get()) return null;

        return evaluate(c.position(), false, Double.NaN, null);
    }

    private Eval evaluate(Vec3 center, boolean placing, double minOverride, LivingEntity only) {
        List<LivingEntity> pool = only != null ? List.of(only) : targets;
        List<Eval> candidates = new ArrayList<>();

        for (LivingEntity t : pool) {
            if (!t.isAlive() || t.distanceToSqr(center) > 100) continue;

            double td = damage(t, center);
            Lethality lethality = classify(t, td);

            if (lethality != Lethality.None || td >= minFor(t, placing, minOverride)) {
                candidates.add(new Eval(t, td, lethality));
            }
        }

        if (candidates.isEmpty()) return null;
        if (antiFriendKill.get() && friendWouldDie(center)) return null;

        double self = damage(mc.player, center);
        Eval best = null;

        for (Eval e : candidates) {
            if (reject(self, e.damage(), e.lethality(), placing, minFor(e.target(), placing, minOverride))) continue;
            if (e.better(best)) best = e;
        }

        return best;
    }

    private boolean reject(double self, double td, Lethality lethality, boolean placing, double min) {
        double myHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();

        if (antiSuicide.get() && self >= myHealth
            && !(lethality == Lethality.Kill && mc.player.isHolding(Items.TOTEM_OF_UNDYING))) return true;

        if (lethality == Lethality.Kill) return false;

        if (placing && self > 0 && lethality != Lethality.Pop && td / self < damageRatio.get()) return true;
        if (self > (placing ? maxPlaceSelf.get() : maxBreakSelf.get())) return true;

        return lethality != Lethality.Pop && td < min;
    }

    private double minFor(LivingEntity t, boolean placing, double override) {
        double min = Double.isNaN(override) ? (placing ? minPlaceDamage.get() : minBreakDamage.get()) : override;

        if (Double.isNaN(override) && facePlaceActive(t)) min = Math.min(min, 0.6);
        return Math.min(min, health(t));
    }

    private Lethality classify(LivingEntity t, double damage) {
        if (damage < health(t)) return Lethality.None;

        boolean totem = t.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || t.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        return totem ? Lethality.Pop : Lethality.Kill;
    }

    private boolean friendWouldDie(Vec3 center) {
        for (Player f : friends) {
            if (f.distanceToSqr(center) <= 144 && damage(f, center) >= health(f)) return true;
        }
        return false;
    }

    private boolean nearTarget(Vec3 center) {
        for (LivingEntity t : targets) {
            if (t.distanceToSqr(center) <= 100) return true;
        }
        return false;
    }

    private double health(LivingEntity e) {
        return e.getHealth() + e.getAbsorptionAmount();
    }

    private boolean facePlaceActive(LivingEntity t) {
        if (!facePlace.get()) return false;
        if (health(t) <= facePlaceHealth.get()) return true;
        if (mc.level.getBlockState(t.blockPosition()).is(Blocks.NETHER_PORTAL)) return true;

        for (EquipmentSlot slot : ARMOR) {
            ItemStack stack = t.getItemBySlot(slot);
            if (stack.isDamageableItem()
                && 100.0 * (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage() <= facePlaceArmor.get()) {
                return true;
            }
        }

        return false;
    }

    private void attack(EndCrystal crystal) {
        attacked.add(crystal.getId());
        lastBreak = ticks;

        Runnable action = () -> {
            mc.gameMode.attack(mc.player, crystal);
            mc.player.swing(InteractionHand.MAIN_HAND);
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(crystal), Rotations.getPitch(crystal), 50, action);
        else action.run();
    }

    private boolean placeCrystal(BlockPos base) {
        InteractionHand hand = InteractionHand.MAIN_HAND;
        int slot = -1;

        if (mc.player.getOffhandItem().is(Items.END_CRYSTAL)) {
            hand = InteractionHand.OFF_HAND;
        } else if (!mc.player.getMainHandItem().is(Items.END_CRYSTAL)) {
            FindItemResult crystals = InvUtils.findInHotbar(Items.END_CRYSTAL);
            if (!crystals.found()) return false;
            slot = crystals.slot();
        }

        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false);
        int swapSlot = slot;
        InteractionHand useHand = hand;

        Runnable action = () -> {
            int prev = mc.player.getInventory().getSelectedSlot();
            if (swapSlot != -1) mc.player.connection.send(new ServerboundSetCarriedItemPacket(swapSlot));
            mc.player.connection.send(new ServerboundUseItemOnPacket(useHand, hit, 0));
            mc.player.connection.send(new ServerboundSwingPacket(useHand));
            if (swapSlot != -1) mc.player.connection.send(new ServerboundSetCarriedItemPacket(prev));
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(base), Rotations.getPitch(base), 50, action);
        else action.run();

        renderPos = base.above();
        renderTick = ticks;
        return true;
    }

    private boolean isBase(BlockPos base) {
        BlockState state = mc.level.getBlockState(base);
        if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) return false;

        if (mineIgnore.get()) {
            SpeedMinev1 speedMine = Modules.get().get(SpeedMinev1.class);
            if (speedMine != null && speedMine.isActive() && speedMine.isMiningBlock(base)) return false;
        }

        return true;
    }

    private boolean canPlace(BlockPos base) {
        BlockPos up = base.above();
        if (!mc.level.getBlockState(up).isAir()) return false;

        AABB box = new AABB(up.getX(), up.getY(), up.getZ(), up.getX() + 1, up.getY() + 2, up.getZ() + 1);
        return mc.level.getEntities((Entity) null, box).isEmpty();
    }

    private EndCrystal crystalAt(BlockPos pos) {
        return mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(pos)).stream().findFirst().orElse(null);
    }

    private Vec3 crystalCenter(BlockPos base) {
        return new Vec3(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }

    private double damage(LivingEntity e, Vec3 center) {
        double dist = Math.sqrt(e.distanceToSqr(center)) / 12.0;
        if (dist > 1.0) return 0;

        double impact = (1.0 - dist) * exposure(e, center);
        double dmg = (impact * impact + impact) / 2.0 * 84.0 + 1.0;

        Difficulty difficulty = mc.level.getDifficulty();
        if (difficulty == Difficulty.PEACEFUL) return 0;
        if (difficulty == Difficulty.EASY) dmg = Math.min(dmg / 2.0 + 1.0, dmg);
        else if (difficulty == Difficulty.HARD) dmg *= 1.5;

        double armor = e.getArmorValue();
        double toughness = e.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        return dmg * (1.0 - Mth.clamp(Math.max(armor / 5.0, armor - dmg / (2.0 + toughness / 4.0)), 0, 20) / 25.0);
    }

    private double exposure(LivingEntity e, Vec3 center) {
        AABB bb = e.getBoundingBox();
        int seen = 0, total = 0;

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 4; j++) {
                for (int k = 0; k < 3; k++) {
                    Vec3 point = new Vec3(
                        Mth.lerp(i / 2.0, bb.minX, bb.maxX),
                        Mth.lerp(j / 3.0, bb.minY, bb.maxY),
                        Mth.lerp(k / 2.0, bb.minZ, bb.maxZ)
                    );

                    total++;
                    if (mc.level.clip(new ClipContext(point, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e)).getType() == HitResult.Type.MISS) seen++;
                }
            }
        }

        return (double) seen / total;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || renderPos == null || ticks - renderTick > 15) return;
        event.renderer.box(renderPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    @Override
    public String getInfoString() {
        return targets.isEmpty() ? null : targets.get(0).getName().getString();
    }
}
