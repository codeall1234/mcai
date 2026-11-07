package ios.mcai;

import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.InetSocketAddress;

public class Mcai implements ModInitializer {
    public static final String MOD_ID = "mcai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static MinecraftServer serverInstance;

    public static class HitRecord {
        public String hitType = "none";
        public float damage = 0.0f;
        public int tick = 0;
    }
    public static final HitRecord hitRecordBot1 = new HitRecord();
    public static final HitRecord hitRecordBot2 = new HitRecord();

    public static void recordHit(String attackerName, String hitType, float damage) {
        HitRecord rec = attackerName.equalsIgnoreCase("bot1") ? hitRecordBot1 : hitRecordBot2;
        rec.hitType = hitType;
        rec.damage = damage;
        rec.tick = tickCounter;
    }

    private AiWebSocketServer serverA;
    private AiWebSocketServer serverB;
    private static int tickCounter = 0;
    private int bot1RespawnCooldown = 0;
    private int bot2RespawnCooldown = 0;

    @Override
    public void onInitialize() {
        LOGGER.info("[MCAI] Server AI Bridge Initialized!");

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
            LOGGER.info("[MCAI] Singleplayer / Dedicated Server Started. Initializing Carpet Bot Duel Arena...");
            CarpetBotHelper.init();
            HealthDisplayHelper.init(server);

            // Start WebSocket server A (Port 44444 for bot1)
            try {
                if (serverA != null) {
                    serverA.stop();
                }
                serverA = new AiWebSocketServer(new InetSocketAddress(44444), "bot1");
                serverA.start();
            } catch (Exception e) {
                LOGGER.error("[MCAI] Failed to start WebSocket Server A (44444): " + e.getMessage());
            }

            // Start WebSocket server B (Port 44445 for bot2)
            try {
                if (serverB != null) {
                    serverB.stop();
                }
                serverB = new AiWebSocketServer(new InetSocketAddress(44445), "bot2");
                serverB.start();
            } catch (Exception e) {
                LOGGER.error("[MCAI] Failed to start WebSocket Server B (44445): " + e.getMessage());
            }

            // Enable Carpet fake player command and spawn both bots
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            spawnBots(server);
        });

        // Auto-equip kit on player join (both Carpet bots and players running /kit)
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer p = handler.getPlayer();
            String name = p.getName().getString();
            if (name.equalsIgnoreCase("bot1") || name.equalsIgnoreCase("bot2")) {
                equipKit(server, p);
            }
        });

        // Auto-equip kit on respawn
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            String name = newPlayer.getName().getString();
            if (name.equalsIgnoreCase("bot1") || name.equalsIgnoreCase("bot2")) {
                if (serverInstance != null) {
                    equipKit(serverInstance, newPlayer);
                }
            }
        });

        // Restrict PvP combat exclusively to bot1 and bot2 (models only target each other, never spectators/mobs)
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, damageSource, amount) -> {
            if (entity instanceof ServerPlayer victim) {
                String victimName = victim.getName().getString();
                boolean victimIsBot1 = victimName.equalsIgnoreCase("bot1");
                boolean victimIsBot2 = victimName.equalsIgnoreCase("bot2");

                if (victimIsBot1 || victimIsBot2) {
                    if (damageSource.getEntity() instanceof ServerPlayer attacker) {
                        String attackerName = attacker.getName().getString();
                        boolean validFight = (victimIsBot1 && attackerName.equalsIgnoreCase("bot2")) ||
                                             (victimIsBot2 && attackerName.equalsIgnoreCase("bot1"));
                        if (validFight) {
                            // Classify hit: Critical, Sprint, Weak, or Normal
                            // Vanilla Critical Hit conditions:
                            // 1. Moving downward in air
                            // 2. Not on ground
                            // 3. Not sprinting
                            // 4. Fully charged attack (amount >= 8.5 with Sharpness 5 diamond sword)
                            boolean isFalling = attacker.fallDistance > 0.0f || attacker.getDeltaMovement().y < -0.05;
                            boolean isAirborne = !attacker.onGround();
                            boolean isNotSprinting = !attacker.isSprinting();
                            boolean isCrit = isFalling && isAirborne && isNotSprinting && (amount >= 8.0f);
                            boolean isSprint = attacker.isSprinting() && (amount >= 5.5f);
                            boolean isWeak = (amount < 4.5f);

                            String hitType = "normal";
                            if (isCrit) {
                                hitType = "crit";
                            } else if (isSprint) {
                                hitType = "sprint";
                            } else if (isWeak) {
                                hitType = "weak";
                            }

                            recordHit(attackerName, hitType, amount);
                            return true;
                        }
                    }
                    return false; // Denies all damage from non-opponents (spectators, other players, mobs, stray damage)
                }

                // If victim is a player (e.g. spectator ios27), bots cannot damage them
                if (damageSource.getEntity() instanceof ServerPlayer attacker) {
                    String attackerName = attacker.getName().getString();
                    if (attackerName.equalsIgnoreCase("bot1") || attackerName.equalsIgnoreCase("bot2")) {
                        return false;
                    }
                }
            }
            return true;
        });

        // Register /kit and /mcai in-game commands
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(net.minecraft.commands.Commands.literal("kit")
                .executes(context -> {
                    CommandSourceStack src = context.getSource();
                    MinecraftServer srv = src.getServer();
                    if (src.isPlayer()) {
                        equipKit(srv, src.getPlayer());
                    }
                    for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                        String pName = p.getName().getString();
                        if (pName.equalsIgnoreCase("bot1") || pName.equalsIgnoreCase("bot2")) {
                            equipKit(srv, p);
                        }
                    }
                    src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("§a[MCAI] Equipped official Sword PvP kit on players & bots!"), true);
                    return 1;
                })
            );

            dispatcher.register(net.minecraft.commands.Commands.literal("mcai")
                .then(net.minecraft.commands.Commands.literal("spawn")
                    .executes(context -> {
                        CommandSourceStack src = context.getSource();
                        MinecraftServer srv = src.getServer();
                        spawnBots(srv);
                        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("§a[MCAI] Successfully spawned Carpet bots bot1 & bot2!"), true);
                        return 1;
                    })
                )
                .then(net.minecraft.commands.Commands.literal("reset")
                    .executes(context -> {
                        CommandSourceStack src = context.getSource();
                        MinecraftServer srv = src.getServer();
                        resetArena(srv);
                        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("§a[MCAI] Duel arena reset!"), true);
                        return 1;
                    })
                )
                .then(net.minecraft.commands.Commands.literal("kit")
                    .executes(context -> {
                        CommandSourceStack src = context.getSource();
                        MinecraftServer srv = src.getServer();
                        if (src.isPlayer()) {
                            equipKit(srv, src.getPlayer());
                        }
                        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                            String pName = p.getName().getString();
                            if (pName.equalsIgnoreCase("bot1") || pName.equalsIgnoreCase("bot2")) {
                                equipKit(srv, p);
                            }
                        }
                        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("§a[MCAI] Equipped official Sword PvP kit!"), true);
                        return 1;
                    })
                )
            );
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            serverInstance = null;
            LOGGER.info("[MCAI] Server stopping. Closing WebSocket servers...");
            try {
                if (serverA != null) serverA.stop();
                if (serverB != null) serverB.stop();
            } catch (Exception ignored) {}
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (bot1RespawnCooldown > 0) bot1RespawnCooldown--;
            if (bot2RespawnCooldown > 0) bot2RespawnCooldown--;
            
            ServerPlayer bot1 = null;
            ServerPlayer bot2 = null;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                String pName = p.getName().getString();
                if (pName.equalsIgnoreCase("bot1")) bot1 = p;
                if (pName.equalsIgnoreCase("bot2")) bot2 = p;
            }

            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();

            // Periodic cleanup of dropped items and XP orbs every 100 ticks (5s at 20 TPS) to prevent memory leak
            if (tickCounter % 100 == 0) {
                server.getCommands().performPrefixedCommand(source, "kill @e[type=item]");
                server.getCommands().performPrefixedCommand(source, "kill @e[type=experience_orb]");
            }

            // Auto-respawn / keep Carpet fake players alive with debounce so Carpet isn't flooded
            if (tickCounter % 5 == 0) {
                if ((bot1 == null || !bot1.isAlive()) && bot1RespawnCooldown <= 0) {
                    server.getCommands().performPrefixedCommand(source, "player bot1 spawn at 0 115 -4");
                    server.getCommands().performPrefixedCommand(source, "player bot1 survival");
                    server.getCommands().performPrefixedCommand(source, "gamemode survival bot1");
                    server.getCommands().performPrefixedCommand(source, "tp bot1 0.5 115 -4.5 0 0");
                    bot1RespawnCooldown = 15;
                }
                if ((bot2 == null || !bot2.isAlive()) && bot2RespawnCooldown <= 0) {
                    server.getCommands().performPrefixedCommand(source, "player bot2 spawn at 0 115 4");
                    server.getCommands().performPrefixedCommand(source, "player bot2 survival");
                    server.getCommands().performPrefixedCommand(source, "gamemode survival bot2");
                    server.getCommands().performPrefixedCommand(source, "tp bot2 0.5 115 4.5 180 0");
                    bot2RespawnCooldown = 15;
                }
            }

            // GUARANTEE SURVIVAL MODE AND OFFICIAL SWORD KIT (NATURAL HUNGER & HEALTH DEPLETION)
            if (bot1 != null && bot1.isAlive()) {
                if (bot1.gameMode.getGameModeForPlayer() != net.minecraft.world.level.GameType.SURVIVAL) {
                    bot1.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                }
                bot1.getAbilities().invulnerable = false;
                if (isMissingKit(bot1)) {
                    equipKit(server, bot1);
                }
            }
            if (bot2 != null && bot2.isAlive()) {
                if (bot2.gameMode.getGameModeForPlayer() != net.minecraft.world.level.GameType.SURVIVAL) {
                    bot2.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                }
                bot2.getAbilities().invulnerable = false;
                if (isMissingKit(bot2)) {
                    equipKit(server, bot2);
                }
            }

            // Watchdog timer: If Python hasn't replied in 3 server ticks, release flow control to prevent deadlock
            if (serverA != null && serverA.awaitingAction) {
                serverA.awaitingTicks++;
                if (serverA.awaitingTicks > 3) {
                    serverA.awaitingAction = false;
                    serverA.awaitingTicks = 0;
                }
            }
            if (serverB != null && serverB.awaitingAction) {
                serverB.awaitingTicks++;
                if (serverB.awaitingTicks > 3) {
                    serverB.awaitingAction = false;
                    serverB.awaitingTicks = 0;
                }
            }

            // Execute all queued server commands from AI clients without dropping any
            while (serverA != null && !serverA.pendingCommands.isEmpty()) {
                String cmd = serverA.pendingCommands.poll();
                if (cmd != null) {
                    if (cmd.equalsIgnoreCase("/mcai reset") || cmd.equalsIgnoreCase("reset")) {
                        resetArena(server);
                    } else {
                        server.getCommands().performPrefixedCommand(source, cmd.startsWith("/") ? cmd.substring(1) : cmd);
                    }
                }
            }
            while (serverB != null && !serverB.pendingCommands.isEmpty()) {
                String cmd = serverB.pendingCommands.poll();
                if (cmd != null) {
                    if (cmd.equalsIgnoreCase("/mcai reset") || cmd.equalsIgnoreCase("reset")) {
                        resetArena(server);
                    } else {
                        server.getCommands().performPrefixedCommand(source, cmd.startsWith("/") ? cmd.substring(1) : cmd);
                    }
                }
            }

            // 1. EXTRACT TELEMETRY FOR BOT 1 (Facing BOT 2) - safely sends 0 HP if dead
            if (serverA != null && serverA.currentConnection != null && serverA.currentConnection.isOpen() && !serverA.awaitingAction) {
                JsonObject stateA = buildBotState(bot1, bot2);
                serverA.currentConnection.send(stateA.toString());
                serverA.awaitingAction = true;
            }

            // 2. EXTRACT TELEMETRY FOR BOT 2 (Facing BOT 1) - safely sends 0 HP if dead
            if (serverB != null && serverB.currentConnection != null && serverB.currentConnection.isOpen() && !serverB.awaitingAction) {
                JsonObject stateB = buildBotState(bot2, bot1);
                serverB.currentConnection.send(stateB.toString());
                serverB.awaitingAction = true;
            }

            // 3. APPLY ACTIONS TO BOT 1 (from serverA)
            if (serverA != null && bot1 != null && bot1.isAlive()) {
                CarpetBotHelper.applyActions(server, bot1, bot2, serverA, "bot1");
            }

            // 4. APPLY ACTIONS TO BOT 2 (from serverB)
            if (serverB != null && bot2 != null && bot2.isAlive()) {
                CarpetBotHelper.applyActions(server, bot2, bot1, serverB, "bot2");
            }

            // 5. UPDATE REAL-TIME HEALTH DISPLAY ABOVE HEADS (for spectators & players)
            HealthDisplayHelper.update(server);
        });
    }

    public static void spawnBots(MinecraftServer server) {
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "carpet commandPlayer true");
        server.getCommands().performPrefixedCommand(source, "gamerule doImmediateRespawn true");
        server.getCommands().performPrefixedCommand(source, "gamerule keepInventory true");
        server.getCommands().performPrefixedCommand(source, "gamerule doMobLoot false");
        server.getCommands().performPrefixedCommand(source, "gamerule doEntityDrops false");
        server.getCommands().performPrefixedCommand(source, "gamerule doTileDrops false");
        server.getCommands().performPrefixedCommand(source, "gamerule disableRaids true");
        server.getCommands().performPrefixedCommand(source, "gamerule doWeatherCycle false");
        server.getCommands().performPrefixedCommand(source, "gamerule doDaylightCycle false");
        server.getCommands().performPrefixedCommand(source, "gamerule pvp true");
        server.getCommands().performPrefixedCommand(source, "defaultgamemode survival");
        server.getCommands().performPrefixedCommand(source, "time set noon");
        server.getCommands().performPrefixedCommand(source, "weather clear");
        server.getCommands().performPrefixedCommand(source, "gamerule doMobSpawning false");
        server.getCommands().performPrefixedCommand(source, "gamerule doPatrolSpawning false");
        server.getCommands().performPrefixedCommand(source, "gamerule doTraderSpawning false");
        server.getCommands().performPrefixedCommand(source, "kill @e[type=item]");
        server.getCommands().performPrefixedCommand(source, "kill @e[type=experience_orb]");
        server.getCommands().performPrefixedCommand(source, "kill @e[type=!minecraft:player]");
        server.getCommands().performPrefixedCommand(source, "op ios27");

        // 1. Convert duel area to official PLAINS biome & flatten grass arena
        server.getCommands().performPrefixedCommand(source, "fillbiome -35 50 -35 35 160 35 minecraft:plains");
        server.getCommands().performPrefixedCommand(source, "fill -30 115 -30 30 140 30 air");
        server.getCommands().performPrefixedCommand(source, "fill -30 114 -30 30 114 30 grass_block");
        server.getCommands().performPrefixedCommand(source, "fill -30 100 -30 30 113 30 dirt");
        server.getCommands().performPrefixedCommand(source, "setworldspawn 0 115 0");

        // 2. Spawn and position bots facing each other
        server.getCommands().performPrefixedCommand(source, "player bot1 spawn at 0 115 -4");
        server.getCommands().performPrefixedCommand(source, "player bot2 spawn at 0 115 4");
        server.getCommands().performPrefixedCommand(source, "player bot1 survival");
        server.getCommands().performPrefixedCommand(source, "player bot2 survival");
        server.getCommands().performPrefixedCommand(source, "gamemode survival bot1");
        server.getCommands().performPrefixedCommand(source, "gamemode survival bot2");

        server.getCommands().performPrefixedCommand(source, "tp bot1 0.5 115 -4.5 0 0");
        server.getCommands().performPrefixedCommand(source, "tp bot2 0.5 115 4.5 180 0");

        // Equip kit directly in memory
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String pName = p.getName().getString();
            if (pName.equalsIgnoreCase("bot1") || pName.equalsIgnoreCase("bot2")) {
                p.setHealth(20.0f);
                p.getFoodData().setFoodLevel(20);
                p.getFoodData().setSaturation(2.0f); // Low starting saturation: natural exhaustion & combat damage stick!
                p.removeAllEffects();
                equipKit(server, p);
            }
        }
        HealthDisplayHelper.init(server);
        HealthDisplayHelper.reset();
        HealthDisplayHelper.update(server);
    }

    public static boolean isMissingKit(ServerPlayer bot) {
        if (bot == null || !bot.isAlive()) return false;
        return bot.getMainHandItem().isEmpty()
            || !bot.getMainHandItem().is(Items.DIAMOND_SWORD)
            || bot.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
            || bot.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
            || bot.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
            || bot.getItemBySlot(EquipmentSlot.FEET).isEmpty();
    }

    public static void equipKit(MinecraftServer server, ServerPlayer bot) {
        if (bot == null || !bot.isAlive()) return;

        bot.getInventory().clearContent();

        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
        ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
        ItemStack legs = new ItemStack(Items.DIAMOND_LEGGINGS);
        ItemStack boots = new ItemStack(Items.DIAMOND_BOOTS);

        try {
            var registry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            var sharpness = registry.getOrThrow(Enchantments.SHARPNESS);
            var unbreaking = registry.getOrThrow(Enchantments.UNBREAKING);
            var protection = registry.getOrThrow(Enchantments.PROTECTION);

            sword.enchant(sharpness, 5);
            sword.enchant(unbreaking, 3);
            helmet.enchant(protection, 4);
            helmet.enchant(unbreaking, 3);
            chest.enchant(protection, 4);
            chest.enchant(unbreaking, 3);
            legs.enchant(protection, 4);
            legs.enchant(unbreaking, 3);
            boots.enchant(protection, 4);
            boots.enchant(unbreaking, 3);

            sword.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
            helmet.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
            chest.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
            legs.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
            boots.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        } catch (Throwable t) {
            LOGGER.error("[MCAI] Enchanting note: " + t.getMessage());
        }

        ItemStack food = new ItemStack(Items.COOKED_BEEF, 64);
        bot.setItemSlot(EquipmentSlot.MAINHAND, sword.copy());
        bot.setItemSlot(EquipmentSlot.OFFHAND, food.copy());
        bot.setItemSlot(EquipmentSlot.HEAD, helmet);
        bot.setItemSlot(EquipmentSlot.CHEST, chest);
        bot.setItemSlot(EquipmentSlot.LEGS, legs);
        bot.setItemSlot(EquipmentSlot.FEET, boots);

        bot.getInventory().setItem(0, sword.copy());
        bot.getInventory().setItem(1, food.copy());
        for (int i = 2; i < 9; i++) {
            bot.getInventory().setItem(i, sword.copy());
        }

        bot.containerMenu.broadcastChanges();
        bot.inventoryMenu.broadcastChanges();
    }

    public static void resetArena(MinecraftServer server) {
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "fill -30 114 -30 30 114 30 grass_block");
        server.getCommands().performPrefixedCommand(source, "gamerule keepInventory true");
        server.getCommands().performPrefixedCommand(source, "gamerule doMobLoot false");
        server.getCommands().performPrefixedCommand(source, "gamerule doImmediateRespawn true");
        server.getCommands().performPrefixedCommand(source, "gamerule pvp true");
        server.getCommands().performPrefixedCommand(source, "gamerule doMobSpawning false");
        server.getCommands().performPrefixedCommand(source, "gamerule doPatrolSpawning false");
        server.getCommands().performPrefixedCommand(source, "gamerule doTraderSpawning false");
        server.getCommands().performPrefixedCommand(source, "kill @e[type=item]");
        server.getCommands().performPrefixedCommand(source, "kill @e[type=experience_orb]");
        server.getCommands().performPrefixedCommand(source, "kill @e[type=!minecraft:player]");

        server.getCommands().performPrefixedCommand(source, "player bot1 spawn at 0 115 -4");
        server.getCommands().performPrefixedCommand(source, "player bot2 spawn at 0 115 4");
        server.getCommands().performPrefixedCommand(source, "player bot1 survival");
        server.getCommands().performPrefixedCommand(source, "player bot2 survival");
        server.getCommands().performPrefixedCommand(source, "gamemode survival bot1");
        server.getCommands().performPrefixedCommand(source, "gamemode survival bot2");

        server.getCommands().performPrefixedCommand(source, "tp bot1 0.5 115 -4.5 0 0");
        server.getCommands().performPrefixedCommand(source, "tp bot2 0.5 115 4.5 180 0");

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String name = p.getName().getString();
            if (name.equalsIgnoreCase("bot1") || name.equalsIgnoreCase("bot2")) {
                p.setHealth(20.0f);
                p.getFoodData().setFoodLevel(20);
                p.getFoodData().setSaturation(2.0f); // Low starting saturation so combat damage sticks!
                p.removeAllEffects();
                equipKit(server, p);
            }
        }
        HealthDisplayHelper.reset();
        HealthDisplayHelper.update(server);
    }

    private JsonObject buildBotState(ServerPlayer self, ServerPlayer target) {
        JsonObject state = new JsonObject();
        boolean selfAlive = (self != null && self.isAlive() && !self.isRemoved());
        boolean targetAlive = (target != null && target.isAlive() && !target.isRemoved());

        state.addProperty("health", selfAlive ? self.getHealth() : 0.0f);
        state.addProperty("cooldown", selfAlive ? (1.0f - self.getAttackStrengthScale(0.0f)) : 0.0f);

        double dist = (selfAlive && targetAlive) ? self.distanceTo(target) : 100.0;
        state.addProperty("distanceToOpponent", dist);

        double yawDiff = 0.0;
        double pitchDiff = 0.0;
        boolean targetingEntity = false;

        if (selfAlive && targetAlive) {
            double dx = target.getX() - self.getX();
            double dy = target.getEyeY() - self.getEyeY();
            double dz = target.getZ() - self.getZ();

            double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double distanceXZ = Math.sqrt(dx * dx + dz * dz);
            double targetPitch = Math.toDegrees(Math.atan2(-dy, distanceXZ));

            yawDiff = targetYaw - self.getYRot();
            while (yawDiff > 180) yawDiff -= 360;
            while (yawDiff < -180) yawDiff += 360;

            pitchDiff = targetPitch - self.getXRot();
            targetingEntity = (dist <= 3.8 && Math.abs(yawDiff) < 30.0 && Math.abs(pitchDiff) < 30.0);
        }

        state.addProperty("yawDiff", yawDiff);
        state.addProperty("pitchDiff", pitchDiff);
        state.addProperty("targetingEntity", targetingEntity);
        state.addProperty("enemyHealth", targetAlive ? target.getHealth() : 0.0f);

        // ---- EXPANDED TELEMETRY FOR ACCELERATED LEARNING ----

        // Own velocity & hunger state
        if (selfAlive) {
            net.minecraft.world.phys.Vec3 vel = self.getDeltaMovement();
            state.addProperty("velX", vel.x);
            state.addProperty("velY", vel.y); // Negative when falling (critical hit window!)
            state.addProperty("velZ", vel.z);
            state.addProperty("isSprinting", self.isSprinting());
            state.addProperty("isOnGround", self.onGround());
            state.addProperty("foodLevel", self.getFoodData().getFoodLevel());
            state.addProperty("saturation", self.getFoodData().getSaturationLevel());
        } else {
            state.addProperty("velX", 0.0);
            state.addProperty("velY", 0.0);
            state.addProperty("velZ", 0.0);
            state.addProperty("isSprinting", false);
            state.addProperty("isOnGround", true);
            state.addProperty("foodLevel", 0);
            state.addProperty("saturation", 0.0f);
        }

        // Enemy velocity projected onto self→enemy axis (approach speed + lateral speed)
        if (selfAlive && targetAlive) {
            double dx = target.getX() - self.getX();
            double dz = target.getZ() - self.getZ();
            double distXZ = Math.sqrt(dx * dx + dz * dz);

            net.minecraft.world.phys.Vec3 enemyVel = target.getDeltaMovement();
            if (distXZ > 0.01) {
                // Unit vector from self to target
                double ux = dx / distXZ;
                double uz = dz / distXZ;
                // Approach speed: positive = enemy moving towards us
                double approachSpeed = -(enemyVel.x * ux + enemyVel.z * uz);
                // Lateral speed: perpendicular component (strafing)
                double lateralSpeed = -enemyVel.x * uz + enemyVel.z * ux;
                state.addProperty("enemyApproachSpeed", approachSpeed);
                state.addProperty("enemyLateralSpeed", lateralSpeed);
            } else {
                state.addProperty("enemyApproachSpeed", 0.0);
                state.addProperty("enemyLateralSpeed", 0.0);
            }
            state.addProperty("enemySprinting", target.isSprinting());
        } else {
            state.addProperty("enemyApproachSpeed", 0.0);
            state.addProperty("enemyLateralSpeed", 0.0);
            state.addProperty("enemySprinting", false);
        }

        // Recent hit telemetry (crit / sprint / weak / normal)
        String selfName = selfAlive ? self.getName().getString() : "";
        HitRecord myHit = selfName.equalsIgnoreCase("bot1") ? hitRecordBot1 : hitRecordBot2;
        boolean recentHit = (tickCounter - myHit.tick <= 2);
        state.addProperty("lastHitType", recentHit ? myHit.hitType : "none");
        state.addProperty("lastHitDamage", recentHit ? myHit.damage : 0.0f);

        return state;
    }
}