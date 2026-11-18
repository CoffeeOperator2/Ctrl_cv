package com.example.addon.modules; // 🌟 Matches your project directory path

import java.util.concurrent.atomic.AtomicInteger;

public class PacketLimiter {
    private static final AtomicInteger packetCount = new AtomicInteger(0);
    private static long lastResetTime = System.currentTimeMillis();

    // 🛡️ CRITICAL BOUNDARY MARGINS
    private static final int MAX_PACKETS_PER_SECOND = 20;

    public static boolean shouldSend(Object packet) {
        long now = System.currentTimeMillis();

        // Reset our bandwidth budget counter every 1000ms (1 second)
        if (now - lastResetTime >= 1000) {
            packetCount.set(0);
            lastResetTime = now;
        }

        // --- HIGH PRIORITY BYPASS ---
        // If the packet is a critical structural exploit action, always allow it through to prevent desyncs
        if (packet instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
            || packet instanceof net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
            || packet instanceof net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket) {

            packetCount.incrementAndGet();
            return true;
        }

        // --- LOW PRIORITY THROTTLING ---
        // If we are approaching the server's hard anti-cheat spam ceiling, block low-priority packets
        if (packetCount.get() >= MAX_PACKETS_PER_SECOND) {
            if (packet instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot) {
                return false; // Safely drop the rotation packet to protect your slot transaction line
            }
            return false; // Skip execution
        }

        packetCount.incrementAndGet();
        return true;
    }
}
