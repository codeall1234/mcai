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
        aiServer = new AiWebSocketServer(new InetSocketAddress(44444));
        aiServer.start();
        System.out.println("Told the AI WebSocket Server to start...");
        
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;

            // --- 1. THE EYES (Extracting Data) ---
            if (aiServer.currentConnection != null) {
                JsonObject state = new JsonObject();
                
                state.addProperty("health", client.player.getHealth());
                state.addProperty("cooldown", 1.0f - client.player.getAttackStrengthScale(0.0f)); 

                double closestDist = 100.0;
                LivingEntity target = null;
                
                // Find nearest opponent
                for (Entity entity : client.level.getEntities(client.player, client.player.getBoundingBox().inflate(50.0))) {
                    if (entity instanceof LivingEntity livingOpponent) {
                        double dist = client.player.distanceTo(livingOpponent);
                        if (dist < closestDist) {
                            closestDist = dist;
                            target = livingOpponent;
                        }
                    }
                }
                
                state.addProperty("distanceToOpponent", closestDist);

                // NEW: Calculate Angle to Target
                double yawDiff = 0.0;
                double pitchDiff = 0.0;

                if (target != null) {
                    double dx = target.getX() - client.player.getX();
                    double dy = target.getEyeY() - client.player.getEyeY();
                    double dz = target.getZ() - client.player.getZ();
                    
                    // Minecraft Trig math to find exact angle
                    double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
                    double distanceXZ = Math.sqrt(dx * dx + dz * dz);
                    double targetPitch = Math.toDegrees(Math.atan2(-dy, distanceXZ));

                    // Calculate difference from where we are currently looking
                    yawDiff = targetYaw - client.player.getYRot();
                    // Wrap the angle so it doesn't try to spin 360 degrees the wrong way
                    while (yawDiff > 180) yawDiff -= 360;
                    while (yawDiff < -180) yawDiff += 360;
                    
                    pitchDiff = targetPitch - client.player.getXRot();
                }

                state.addProperty("yawDiff", yawDiff);
                state.addProperty("pitchDiff", pitchDiff);

                aiServer.currentConnection.send(state.toString());
            }

            // --- 2. THE HANDS (Executing Commands) ---
            client.options.keyUp.setDown(aiServer.forward);
            client.options.keyDown.setDown(aiServer.backward);
            client.options.keyLeft.setDown(aiServer.left);
            client.options.keyRight.setDown(aiServer.right);
            client.options.keyJump.setDown(aiServer.jump);
            client.options.keyShift.setDown(aiServer.sneak);
            client.options.keySprint.setDown(aiServer.sprint);
            client.options.keyAttack.setDown(aiServer.attack);

            // Apply mouse movement
            if (aiServer.deltaYaw != 0.0f || aiServer.deltaPitch != 0.0f) {

                client.player.setYRot(client.player.getYRot() + aiServer.deltaYaw);

                client.player.setXRot(client.player.getXRot() + aiServer.deltaPitch);

                aiServer.deltaYaw = 0.0f;
                aiServer.deltaPitch = 0.0f;
            }
        });
    }
}