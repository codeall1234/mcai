package ios.mcai;

import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import java.net.InetSocketAddress;

public class McaiClient implements ClientModInitializer {

    private AiWebSocketServer aiServer;

    @Override
    public void onInitializeClient() {
        aiServer = new AiWebSocketServer(new InetSocketAddress(8805));
        aiServer.start();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;

            // --- 1. THE EYES (Extracting Data) ---
            if (aiServer.currentConnection != null) {
                JsonObject state = new JsonObject();
                
                state.addProperty("health", client.player.getHealth());
                state.addProperty("cooldown", 1.0f - client.player.getAttackStrengthScale(0.0f)); 

                // Universal Opponent Finder (Works on Zombies, Vindicators, and other Players)
                double closestDist = 100.0;
                
                // Scan a 50-block box around the AI
                for (Entity entity : client.level.getEntities(client.player, client.player.getBoundingBox().inflate(50.0))) {
                    // Make sure the entity is actually alive (not a dropped item or arrow)
                    if (entity instanceof LivingEntity livingOpponent) {
                        double dist = client.player.distanceTo(livingOpponent);
                        if (dist < closestDist) {
                            closestDist = dist;
                        }
                    }
                }
                state.addProperty("distanceToOpponent", closestDist);

                aiServer.currentConnection.send(state.toString());
            }

            // --- 2. THE HANDS (Executing Commands) ---
            // Mapping the 8 core PvP inputs to the client
            client.options.keyUp.setDown(aiServer.forward);       // W
            client.options.keyDown.setDown(aiServer.backward);    // S
            client.options.keyLeft.setDown(aiServer.left);        // A
            client.options.keyRight.setDown(aiServer.right);      // D
            client.options.keyJump.setDown(aiServer.jump);        // Space
            client.options.keyShift.setDown(aiServer.sneak);      // Shift
            client.options.keySprint.setDown(aiServer.sprint);    // Ctrl (Sprint)

            // Attack handling
            if (aiServer.attack) {
                client.options.keyAttack.setDown(true);           // Left Click
            } else {
                client.options.keyAttack.setDown(false);
            }
        });
    }
}