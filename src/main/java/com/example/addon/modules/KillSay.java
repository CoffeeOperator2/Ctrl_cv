package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class KillSay extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<List<String>> messages = sg.add(new StringListSetting.Builder()
        .name("messages").description("Messages to send. Use {name} for the victim's name.")
        .defaultValue(List.of("gg {name}")).build());
    private final Setting<Boolean> random = sg.add(new BoolSetting.Builder()
        .name("random").description("Pick messages randomly instead of in order.")
        .defaultValue(true).build());
    private final Setting<Boolean> whisper = sg.add(new BoolSetting.Builder()
        .name("whisper").description("Whisper the message to the victim instead of using public chat.")
        .defaultValue(false).build());
    private final Setting<String> whisperCommand = sg.add(new StringSetting.Builder()
        .name("whisper-command").description("Command used to whisper, without the slash (w, msg, tell...).")
        .defaultValue("w").visible(whisper::get).build());
    private final Setting<Boolean> ignoreFriends = sg.add(new BoolSetting.Builder()
        .name("ignore-friends").defaultValue(true).build());
    private final Setting<Integer> delay = sg.add(new IntSetting.Builder()
        .name("delay-ticks").description("Ticks to wait before sending.")
        .defaultValue(0).min(0).sliderMax(100).build());
    private final Setting<Integer> window = sg.add(new IntSetting.Builder()
        .name("kill-window").description("Ticks after your last hit during which a death counts as your kill.")
        .defaultValue(60).min(5).sliderMax(200).build());

    // isCommand = true means "text" is a command without the leading slash.
    private record Pending(int tick, String text, boolean isCommand) {}

    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private int index = 0;
    private int tickCount = 0;
    private Entity lastTarget;
    private int lastHitTick = -9999;

    public KillSay() {
        super(AddonTemplate.CATEGORY, "kill-say", "Sends a chat message when you kill a player.");
    }

    @Override
    public void onActivate() {
        tickCount = 0;
        index = 0;
        queue.clear();
    }

    private String nextLine() {
        List<String> list = messages.get();
        if (list.isEmpty()) return null;
        if (random.get()) return list.get(ThreadLocalRandom.current().nextInt(list.size()));
        return list.get(index++ % list.size());
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.entity instanceof Player) {
            lastTarget = event.entity;
            lastHitTick = tickCount;
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof ClientboundEntityEventPacket packet)) return;
        if (packet.getEventId() != 3 || mc.level == null) return; // 3 = entity death

        Entity entity = packet.getEntity(mc.level);
        if (!(entity instanceof Player victim) || entity != lastTarget) return;
        if (tickCount - lastHitTick > window.get()) return;
        if (ignoreFriends.get() && Friends.get().isFriend(victim)) return;

        String line = nextLine();
        if (line == null) return;

        String name = victim.getName().getString();
        String message = line.replace("{name}", name);
        int when = tickCount + delay.get();

        if (whisper.get()) {
            String cmd = whisperCommand.get().trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            queue.add(new Pending(when, cmd + " " + name + " " + message, true));
        } else {
            queue.add(new Pending(when, message, false));
        }

        lastTarget = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        tickCount++;
        while (!queue.isEmpty() && queue.peek().tick() <= tickCount) {
            Pending p = queue.poll();
            if (p.isCommand()) {
                if (mc.getConnection() != null) mc.getConnection().sendCommand(p.text());
            } else {
                ChatUtils.sendPlayerMsg(p.text());
            }
        }
    }
}
