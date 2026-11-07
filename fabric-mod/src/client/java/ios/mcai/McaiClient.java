package ios.mcai;

import net.fabricmc.api.ClientModInitializer;

public class McaiClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        System.out.println("=================================================");
        System.out.println(" [MCAI] Client Initialized in Spectator Mode!");
        System.out.println(" Carpet Bots (bot1 & bot2) are managed on the server.");
        System.out.println(" Fly freely in spectator/creative mode to watch the duel!");
        System.out.println("=================================================");
    }
}