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

    public WebSocket currentConnection = null;

    public AiWebSocketServer(InetSocketAddress address) {
        super(address);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("Python Script Connected!");
        this.currentConnection = conn;
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("AI Disconnected!");
        this.currentConnection = null;
        this.forward = false; this.backward = false; this.left = false; this.right = false;
        this.jump = false; this.sneak = false; this.sprint = false; this.attack = false;
        this.deltaYaw = 0.0f; this.deltaPitch = 0.0f;
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            
            // Keyboard
            if (json.has("forward")) this.forward = json.get("forward").getAsBoolean();
            if (json.has("backward")) this.backward = json.get("backward").getAsBoolean();
            if (json.has("left")) this.left = json.get("left").getAsBoolean();
            if (json.has("right")) this.right = json.get("right").getAsBoolean();
            if (json.has("jump")) this.jump = json.get("jump").getAsBoolean();
            if (json.has("sneak")) this.sneak = json.get("sneak").getAsBoolean();
            if (json.has("sprint")) this.sprint = json.get("sprint").getAsBoolean();
            if (json.has("attack")) this.attack = json.get("attack").getAsBoolean();
            
            // Mouse
            if (json.has("deltaYaw")) this.deltaYaw = json.get("deltaYaw").getAsFloat();
            if (json.has("deltaPitch")) this.deltaPitch = json.get("deltaPitch").getAsFloat();
            
        } catch (Exception e) {
            System.err.println("Failed to parse AI command: " + message);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("AI WEBSOCKET ERROR ENCOUNTERED:");
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("=========================================");
        System.out.println(" SUCCESS: AI WebSocket Bridge started on port: " + getPort());
        System.out.println("=========================================");
    }
}