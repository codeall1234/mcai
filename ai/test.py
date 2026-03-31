import asyncio
import websockets
import json
import random

async def test_ai_connection():
    uri = "ws://localhost:44444"
    print(f"Attempting to connect to Minecraft at {uri}...")
    
    try:
        async with websockets.connect(uri) as websocket:
            print("Successfully connected! Sending chaos inputs...")
            
            while True:
                # 1. THE EYES
                state_message = await websocket.recv()
                state = json.loads(state_message)
                
                dist = state.get('distanceToOpponent')
                yaw_diff = state.get('yawDiff')
                
                # Print the angle diff so you can see the math working!
                print(f"Nearest Enemy: {dist:.2f} blocks away | Aim is off by {yaw_diff:.2f} degrees")
                
                # 2. THE BRAIN (Full Movement Enabled)
                action = {
                    "forward": random.choice([True, False]),
                    "backward": random.choice([True, False]),
                    "left": random.choice([True, False]),
                    "right": random.choice([True, False]),
                    "jump": random.choice([True, False, False]), 
                    "sneak": random.choice([True, False, False]), # Crouch occasionally
                    "sprint": random.choice([True, False]),
                    "attack": random.choice([True, False]),
                    
                    # Randomly flick the mouse left, right, up, or down by a few degrees
                    "deltaYaw": random.uniform(-10.0, 10.0),
                    "deltaPitch": random.uniform(-5.0, 5.0)
                }
                
                # 3. THE HANDS
                await websocket.send(json.dumps(action))
                await asyncio.sleep(0.05)
                
    except ConnectionRefusedError:
        print("Connection refused. Is Minecraft running?")

asyncio.run(test_ai_connection())