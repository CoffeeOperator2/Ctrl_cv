package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

public class TickShift extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> maxTicks = sgGeneral.add(new IntSetting.Builder()
        .name("max-ticks")
        .description("Maximum number of ticks to bank while standing still.")
        .defaultValue(20)
        .min(1)
        .sliderMax(60)
        .build()
    );

    public final Setting<Double> speedMultiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Timer multiplier when releasing banked ticks.")
        .defaultValue(2.0)
        .min(1.1)
        .sliderMax(5.0)
        .build()
    );

    public final Setting<Boolean> cancelIdlePackets = sgGeneral.add(new BoolSetting.Builder()
        .name("cancel-idle-packets")
        .description("Cancels stationary movement packets while charging to genuinely bank timer balance.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Turns off automatically once all banked ticks are spent.")
        .defaultValue(false)
        .build()
    );

    private double chargedTicks = 0.0;
    private boolean isDischarging = false;

    public TickShift() {
        super(AddonTemplate.CATEGORY, "TickShift", "Banks movement ticks while standing still and releases them via Timer.");
    }

    @Override
    public void onActivate() {
        chargedTicks = 0.0;
        isDischarging = false;
    }

    @Override
    public void onDeactivate() {
        chargedTicks = 0.0;
        isDischarging = false;
        resetTimer();
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null || !cancelIdlePackets.get()) return;

        if (!isDischarging && chargedTicks < maxTicks.get() && event.packet instanceof ServerboundMovePlayerPacket) {
            if (!isPlayerMoving()) {
                event.cancel();
            }
        }
    }

    @EventHandler
    public void onTickPre(TickEvent.Pre event) {
        if (mc.player == null) return;

        Timer timer = Modules.get().get(Timer.class);
        if (timer == null) return;

        if (!isPlayerMoving()) {
            if (isDischarging && chargedTicks <= 0.0 && autoDisable.get()) {
                toggle();
                return;
            }

            isDischarging = false;
            timer.setOverride(Timer.OFF);

            if (chargedTicks < maxTicks.get()) {
                chargedTicks = Math.min(maxTicks.get(), chargedTicks + 1.0);
            }
        } else {
            if (chargedTicks > 0.0) {
                isDischarging = true;
                timer.setOverride(speedMultiplier.get());

                // Exact drain math: e.g., at 1.5x speed, drains 0.5 extra ticks per client tick
                double drain = speedMultiplier.get() - 1.0;
                chargedTicks = Math.max(0.0, chargedTicks - drain);
            } else {
                isDischarging = false;
                timer.setOverride(Timer.OFF);

                if (autoDisable.get()) {
                    toggle();
                }
            }
        }
    }

    private boolean isPlayerMoving() {
        return mc.player.xxa != 0.0f
            || mc.player.zza != 0.0f
            || mc.options.keyJump.isDown()
            || !mc.player.onGround();
    }

    private void resetTimer() {
        Timer timer = Modules.get().get(Timer.class);
        if (timer != null) {
            timer.setOverride(Timer.OFF);
        }
    }

    @Override
    public String getInfoString() {
        int displayTicks = (int) Math.floor(chargedTicks);
        return (displayTicks >= maxTicks.get() ? ChatFormatting.GREEN.toString() : "") + displayTicks;
    }
}
