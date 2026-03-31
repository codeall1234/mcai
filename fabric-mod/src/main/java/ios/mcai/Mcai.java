package ios.mcai;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mcai implements ModInitializer {
    public static final String MOD_ID = "pvpai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("AI Bridge Initialized!");

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Nothing here
        });
    }
}