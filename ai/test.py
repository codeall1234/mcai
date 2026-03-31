import asyncio
import websockets
import json
import random

async def test_ai_connection():
    uri = "ws://localhost:8081"
    print(f"Attempting to connect to Minecraft at {uri}...")
    
    try:
        # Connect to the Fabric Mod
        async with websockets.connect(uri) as websocket:
            print("Successfully connected to the Minecraft AI Bridge!")
            print("Sending random inputs... (Go look at your Minecraft window!)")
            
            while True:
                # 1. THE EYES: Receive the data from Minecraft
                state_message = await websocket.recv()
                state = json.loads(state_message)
                
                # Print what the AI sees to your terminal
                health = state.get('health')
                cooldown = state.get('cooldown')
                dist = state.get('distanceToOpponent')
                print(f"AI Sees -> Health: {health}, Cooldown: {cooldown:.2f}, Nearest Enemy: {dist:.2f} blocks")
                
                # 2. THE BRAIN: Make a random decision
                action = {
                    "forward": random.choice([True, False]),
                    "backward": False,
                    "left": False,
                    "right": False,
                    "jump": random.choice([True, False, False, False]), # Jump 25% of the time
                    "sneak": False,
                    "sprint": random.choice([True, False]),
                    "attack": random.choice([True, False])
                }
                
                # 3. THE HANDS: Send the button presses back to Minecraft
                await websocket.send(json.dumps(action))
                
                # Pause for 1/20th of a second to match Minecraft's tick rate
                await asyncio.sleep(0.05)
                
    except ConnectionRefusedError:
        print("Connection refused. Is Minecraft running and are you in a world?")

# Start the script
asyncio.run(test_ai_connection())