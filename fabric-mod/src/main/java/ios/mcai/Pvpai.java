package com.yourname.pvpai; // Make sure this matches your actual package name!

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Pvpai implements ModInitializer {
    public static final String MOD_ID = "pvpai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("PvP AI Bridge Initialized!");

        // This is the heartbeat of our AI. This event fires 20 times a second (or faster if uncapped).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // TODO: 1. Collect player health, opponent distance, and cooldowns here.
            // TODO: 2. Send that data via WebSocket to Python.
            // TODO: 3. Execute any actions received from Python.
        });
    }
}