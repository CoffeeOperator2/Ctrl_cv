package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TotemPopCounter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> webhookUrl = sgGeneral.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("The Discord Webhook URL path target.")
        .defaultValue("YOUR_WEBHOOK_HERE")
        .build()
    );

    private final Map<String, Integer> popTracker = new HashMap<>();
    private final ExecutorService networkExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public TotemPopCounter() {
        super(AddonTemplate.CATEGORY, "totem-pop-counter", "Logs pop metrics cleanly directly into local chat channel and outputs to a Discord Webhook.");
    }

    @Override
    public void onActivate() {
        popTracker.clear();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;

        // --- STAGE 1: LOG ACCURATE PLAYER TOTEM POPS ---
        if (event.packet instanceof ClientboundEntityEventPacket packet && packet.getEventId() == 35) {
            Entity entity = packet.getEntity(mc.level);
            if (entity instanceof Player player && player != mc.player) {
                String name = player.getName().getString();

                int totalPops = popTracker.getOrDefault(name, 0) + 1;
                popTracker.put(name, totalPops);

                ChatUtils.info("(highlight)" + name + "(default) popped a totem!");

                sendDiscordWebhookAsync(String.format("%s pops %d totems", name, totalPops));
            }
        }
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // --- STAGE 2: BULLETPROOF PROACTIVE DEATH LOOP ---
        // Scans active player instances in render distance dynamically.
        // If a tracked player is dead or removed from the world, log their crystal death to the webhook!
        popTracker.keySet().removeIf(name -> {
            Player trackedPlayer = mc.level.players().stream()
                .filter(p -> p.getName().getString().equals(name))
                .findFirst()
                .orElse(null);

            if (trackedPlayer != null) {
                // Fixed: Utilizing standard public Mojmap entity status methods—if their health hit 0, they were blown away!
                if (!trackedPlayer.isAlive() || trackedPlayer.getHealth() <= 0.0f) {
                    sendDiscordWebhookAsync(String.format("%s was blown away by end crystal", name));
                    return true; // Removes from tracking registry cleanly
                }
                return false;
            }

            // If they completely left rendering range or logged out, clear track profile silently without spamming
            return true;
        });
    }

    private void sendDiscordWebhookAsync(String textPayload) {
        String targetUrl = webhookUrl.get();
        if (targetUrl == null || targetUrl.isEmpty() || targetUrl.equals("YOUR_WEBHOOK_HERE")) return;

        networkExecutor.execute(() -> {
            try {
                URL url = new URL(targetUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                String jsonPayload = "{\"content\": \"" + textPayload.replace("\"", "\\\"") + "\"}";
                byte[] byteStream = jsonPayload.getBytes(StandardCharsets.UTF_8);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(byteStream, 0, byteStream.length);
                }

                connection.getResponseCode();
                connection.disconnect();
            } catch (Exception ignored) {}
        });
    }
}

