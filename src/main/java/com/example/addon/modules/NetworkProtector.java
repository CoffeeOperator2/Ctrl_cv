package com.example.addon.modules;

import com.example.addon.modules.PacketLimiter;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

public class NetworkProtector extends Module {
    public NetworkProtector() {
        // 🌟 Always-on background proxy shield to protect your network bandwidth
        super(Categories.Misc, "network-protector", "Global proxy shield that intercepts and filters outbound packets to prevent spam kicks.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacketSend(PacketEvent.Send event) {
        // Intercepts every single packet leaving your client from any module
        if (!PacketLimiter.shouldSend(event.packet)) {
            // Cancel the event right here if the limiter flags it as redundant spam!
            event.cancel();
        }
    }
}


