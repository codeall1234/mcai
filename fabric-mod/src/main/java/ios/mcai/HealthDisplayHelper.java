package ios.mcai;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.HashMap;
import java.util.Map;

public class HealthDisplayHelper {
    private static final String OBJECTIVE_NAME = "Health";
    private static final Map<String, Float> lastHealthMap = new HashMap<>();
    private static final Map<String, Integer> lastFoodMap = new HashMap<>();

    public static void init(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = scoreboard.addObjective(
                OBJECTIVE_NAME,
                ObjectiveCriteria.DUMMY,
                Component.literal("§c❤"),
                ObjectiveCriteria.RenderType.INTEGER,
                true,
                null
            );
        }
        scoreboard.setDisplayObjective(DisplaySlot.BELOW_NAME, objective);
    }

    public static void update(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            init(server);
            objective = scoreboard.getObjective(OBJECTIVE_NAME);
            if (objective == null) return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) continue;

            String name = player.getName().getString();
            float health = (player.isAlive() && !Float.isNaN(player.getHealth())) ? Math.max(0.0f, player.getHealth()) : 0.0f;
            int food = player.isAlive() ? player.getFoodData().getFoodLevel() : 0;
            Float lastHealth = lastHealthMap.get(name);
            Integer lastFood = lastFoodMap.get(name);

            boolean healthChanged = (lastHealth == null || Math.abs(health - lastHealth) > 0.05f);
            boolean foodChanged = (lastFood == null || food != lastFood);

            if (healthChanged || foodChanged) {
                lastHealthMap.put(name, health);
                lastFoodMap.put(name, food);

                // 1. Update belowName integer score (in hearts: 10 ❤ max)
                int intHearts = (int) Math.ceil(health / 2.0f);
                scoreboard.getOrCreatePlayerScore(player, objective).set(intHearts);

                // 2. Update team suffix for real-time decimal precision & hunger bar
                String teamName = "team_" + name.toLowerCase();
                PlayerTeam team = scoreboard.getPlayerTeam(teamName);
                if (team == null) {
                    team = scoreboard.addPlayerTeam(teamName);
                    scoreboard.addPlayerToTeam(name, team);
                    team.setNameTagVisibility(Team.Visibility.ALWAYS);
                    team.setCollisionRule(Team.CollisionRule.ALWAYS);
                } else if (!team.getPlayers().contains(name)) {
                    scoreboard.addPlayerToTeam(name, team);
                }

                if (!player.isAlive() || health <= 0.0f) {
                    team.setPlayerSuffix(Component.literal(" §8[§40.0 §c☠§8]"));
                } else {
                    float hearts = health / 2.0f;
                    String color = health > 12.0f ? "§a" : (health > 6.0f ? "§e" : "§c");
                    String foodColor = food > 14 ? "§6" : (food > 6 ? "§e" : "§c");
                    team.setPlayerSuffix(Component.literal(String.format(" §8[%s%.1f §c❤ §7(%.1f HP) %s%d🍖§8]", color, hearts, health, foodColor, food)));
                }
            }
        }
    }

    public static void reset() {
        lastHealthMap.clear();
        lastFoodMap.clear();
    }
}
