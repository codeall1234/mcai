package ios.mcai;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import java.lang.reflect.Method;

public class CarpetBotHelper {
    private static boolean initialized = false;
    private static Method getActionPackMethod;
    private static Method setForwardMethod;
    private static Method setStrafingMethod;
    private static Method setSprintingMethod;
    private static Method setSneakingMethod;
    private static Method turnMethod;
    private static Method stopMovementMethod;
    private static Method startMethod;
    private static Object attackActionType;
    private static Object jumpActionType;
    private static Object actionOnce;

    public static void init() {
        if (initialized) return;
        try {
            Class<?> spiClass = Class.forName("carpet.fakes.ServerPlayerInterface");
            getActionPackMethod = spiClass.getMethod("getActionPack");

            Class<?> apClass = Class.forName("carpet.helpers.EntityPlayerActionPack");
            setForwardMethod = apClass.getMethod("setForward", float.class);
            setStrafingMethod = apClass.getMethod("setStrafing", float.class);
            setSprintingMethod = apClass.getMethod("setSprinting", boolean.class);
            setSneakingMethod = apClass.getMethod("setSneaking", boolean.class);
            turnMethod = apClass.getMethod("turn", float.class, float.class);
            stopMovementMethod = apClass.getMethod("stopMovement");

            Class<?> atClass = Class.forName("carpet.helpers.EntityPlayerActionPack$ActionType");
            for (Object constant : atClass.getEnumConstants()) {
                if ("ATTACK".equals(constant.toString())) attackActionType = constant;
                if ("JUMP".equals(constant.toString())) jumpActionType = constant;
            }

            Class<?> actionClass = Class.forName("carpet.helpers.EntityPlayerActionPack$Action");
            Method onceMethod = actionClass.getMethod("once");
            actionOnce = onceMethod.invoke(null);

            Class<?> actionTypeClass = Class.forName("carpet.helpers.EntityPlayerActionPack$ActionType");
            startMethod = apClass.getMethod("start", actionTypeClass, actionClass);

            initialized = true;
            System.out.println("[MCAI] Successfully hooked into Carpet EntityPlayerActionPack!");
        } catch (Throwable t) {
            System.err.println("[MCAI] Notice: Reflection hook into Carpet ActionPack not available (" + t.getMessage() + "), using server command pipeline.");
        }
    }

    public static void applyActions(MinecraftServer server, ServerPlayer bot, ServerPlayer opponent, AiWebSocketServer aiServer, String botName) {
        if (bot == null || !bot.isAlive()) return;

        // 1. Aim / Rotation with smooth human-like camera motion
        if (aiServer.deltaYaw != 0.0f || aiServer.deltaPitch != 0.0f) {
            float currentYaw = bot.getYRot();
            float currentPitch = bot.getXRot();

            float targetYaw = currentYaw + aiServer.deltaYaw;
            float targetPitch = Math.max(-90.0f, Math.min(90.0f, currentPitch + aiServer.deltaPitch));

            // Smooth linear interpolation across ticks
            float smoothedYaw = currentYaw + (targetYaw - currentYaw) * 0.75f;
            float smoothedPitch = currentPitch + (targetPitch - currentPitch) * 0.75f;

            bot.setYRot(smoothedYaw);
            bot.setXRot(smoothedPitch);
            bot.setYHeadRot(smoothedYaw);

            // Natural body trailing: torso only turns if head rotates > 40 degrees away
            float bodyDiff = smoothedYaw - bot.yBodyRot;
            while (bodyDiff > 180.0f) bodyDiff -= 360.0f;
            while (bodyDiff < -180.0f) bodyDiff += 360.0f;
            if (Math.abs(bodyDiff) > 40.0f) {
                bot.setYBodyRot(smoothedYaw - Math.signum(bodyDiff) * 40.0f);
            }

            aiServer.deltaYaw = 0.0f;
            aiServer.deltaPitch = 0.0f;
        }

        // 2. Attack Execution: Strictly target the designated opponent
        if (aiServer.attack) {
            if (opponent != null && opponent.isAlive() && bot.distanceTo(opponent) <= 3.8) {
                // Ensure bot is actually facing generally towards the opponent before landing hit
                double dx = opponent.getX() - bot.getX();
                double dy = opponent.getEyeY() - bot.getEyeY();
                double dz = opponent.getZ() - bot.getZ();
                double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
                double distXZ = Math.sqrt(dx * dx + dz * dz);
                double targetPitch = Math.toDegrees(Math.atan2(-dy, distXZ));

                double yawDiff = targetYaw - bot.getYRot();
                while (yawDiff > 180) yawDiff -= 360;
                while (yawDiff < -180) yawDiff += 360;
                double pitchDiff = targetPitch - bot.getXRot();

                if (Math.abs(yawDiff) < 60.0 && Math.abs(pitchDiff) < 60.0) {
                    bot.attack(opponent);
                    opponent.invulnerableTime = 0; // Clear immunity frames so rapid hits connect
                }
            }
            bot.swing(InteractionHand.MAIN_HAND, true);
            aiServer.attack = false; // Reset attack so each action is a distinct swing and weapon cooldown recharges
        }

        // 3. Movement (Crouching completely removed)
        boolean appliedWithActionPack = false;
        if (initialized && getActionPackMethod != null) {
            try {
                Object ap = getActionPackMethod.invoke(bot);
                if (ap != null) {
                    float forward = aiServer.forward ? 1.0f : (aiServer.backward ? -1.0f : 0.0f);
                    float strafe = aiServer.left ? 1.0f : (aiServer.right ? -1.0f : 0.0f);
                    setForwardMethod.invoke(ap, forward);
                    setStrafingMethod.invoke(ap, strafe);
                    setSprintingMethod.invoke(ap, aiServer.sprint);
                    setSneakingMethod.invoke(ap, false); // Crouching removed
                    if (aiServer.jump && startMethod != null && jumpActionType != null && actionOnce != null) {
                        startMethod.invoke(ap, jumpActionType, actionOnce);
                    }
                    appliedWithActionPack = true;
                }
            } catch (Throwable ignored) {}
        }

        // Fallback: Use server command dispatcher
        if (!appliedWithActionPack && server != null) {
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            if (aiServer.forward) {
                server.getCommands().performPrefixedCommand(source, "player " + botName + " move forward");
            } else if (aiServer.backward) {
                server.getCommands().performPrefixedCommand(source, "player " + botName + " move backward");
            } else if (aiServer.left) {
                server.getCommands().performPrefixedCommand(source, "player " + botName + " move left");
            } else if (aiServer.right) {
                server.getCommands().performPrefixedCommand(source, "player " + botName + " move right");
            } else {
                server.getCommands().performPrefixedCommand(source, "player " + botName + " stop");
            }

            if (aiServer.sprint) {
                server.getCommands().performPrefixedCommand(source, "player " + botName + " sprint");
            } else {
                server.getCommands().performPrefixedCommand(source, "player " + botName + " unsprint");
            }

            if (aiServer.jump) {
                server.getCommands().performPrefixedCommand(source, "player " + botName + " jump once");
            }
        }
    }
}
