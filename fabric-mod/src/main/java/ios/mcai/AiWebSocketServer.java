package ios.mcai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;

public class AiWebSocketServer extends WebSocketServer {

    // Keyboard
    public boolean forward = false;
    public boolean backward = false;
    public boolean left = false;
    public boolean right = false;
    public boolean jump = false;
    public boolean sneak = false;
    public boolean sprint = false;
    public boolean attack = false;
    
    // Mouse
    public float deltaYaw = 0.0f;
    public float deltaPitch = 0.0f;

    // Carpet & High TPS Flow-Control
    public volatile boolean awaitingAction = false;
    public int awaitingTicks = 0;
    public final java.util.Queue<String> pendingCommands = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public WebSocket currentConnection = null;
    private final String botName;

    public AiWebSocketServer(InetSocketAddress address, String botName) {
        super(address);
        this.botName = botName;
        setReuseAddr(true);
        setTcpNoDelay(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("AI Connected for [" + botName + "] on port " + getPort() + "!");
        this.currentConnection = conn;
        this.awaitingAction = false;
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("AI Disconnected for [" + botName + "]!");
        this.currentConnection = null;
        this.awaitingAction = false;
        this.forward = false; this.backward = false; this.left = false; this.right = false;
        this.jump = false; this.sneak = false; this.sprint = false; this.attack = false;
        this.deltaYaw = 0.0f; this.deltaPitch = 0.0f;
        this.pendingCommands.clear();
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            
            // In-game command support (e.g. /tick rate 100)
            if (json.has("command")) {
                this.pendingCommands.add(json.get("command").getAsString());
            }

            // Keyboard
            if (json.has("forward")) this.forward = json.get("forward").getAsBoolean();
            if (json.has("backward")) this.backward = json.get("backward").getAsBoolean();
            if (json.has("left")) this.left = json.get("left").getAsBoolean();
            if (json.has("right")) this.right = json.get("right").getAsBoolean();
            if (json.has("jump")) this.jump = json.get("jump").getAsBoolean();
            this.sneak = false; // Crouching completely removed
            if (json.has("sprint")) this.sprint = json.get("sprint").getAsBoolean();
            if (json.has("attack")) this.attack = json.get("attack").getAsBoolean();
            
            // Mouse
            if (json.has("deltaYaw")) this.deltaYaw = json.get("deltaYaw").getAsFloat();
            if (json.has("deltaPitch")) this.deltaPitch = json.get("deltaPitch").getAsFloat();
            
            // Flow-control: action received, ready for next state tick!
            this.awaitingAction = false;
            this.awaitingTicks = 0;
        } catch (Exception e) {
            System.err.println("Failed to parse AI command for " + botName + ": " + message);
            this.awaitingAction = false;
            this.awaitingTicks = 0;
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("AI WEBSOCKET ERROR on [" + botName + "]:");
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("=========================================");
        System.out.println(" SUCCESS: AI WebSocket Bridge started for [" + botName + "] on port: " + getPort());
        System.out.println(" Carpet Flow-Control Enabled (TCP_NODELAY: ON)");
        System.out.println("=========================================");
    }
}
