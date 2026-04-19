import gymnasium as gym
from stable_baselines3 import PPO
import time

# Import your exact environment from your training script!
# (Make sure your training file is named train.py)
from train import MinecraftPvPEnv 

def main():
    print("Connecting to Minecraft...")
    env = MinecraftPvPEnv()

    print("Loading the trained brain (mcai_model_latest.zip)...")
    # Load the model from the zip file
    model = PPO.load("mcai_model_latest")

    print("Brain loaded! Spawning into the world...")
    
    # Get the very first frame of data from the game
    obs, info = env.reset()
    
    try:
        while True:
            # THE MAGIC LINE: deterministic=True
            # This tells the AI: "Stop exploring randomly. Do exactly what you think is best."
            action, _states = model.predict(obs, deterministic=True)
            
            # Send the action to the game and see what happens
            obs, reward, terminated, truncated, info = env.step(action)
            
            # If the AI dies or the time limit runs out, reset the match
            if terminated or truncated:
                print("Match over! Resetting...")
                obs, info = env.reset()
                
    except KeyboardInterrupt:
        print("\nTesting stopped by user.")
    finally:
        env.close()

if __name__ == "__main__":
    main()