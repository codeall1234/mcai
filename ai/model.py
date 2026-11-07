import gymnasium as gym
from stable_baselines3 import PPO
from stable_baselines3.common.vec_env import DummyVecEnv, VecNormalize
import os
import time

# Import the exact environment from the training script
from train import MinecraftPvPEnv 

def main():
    print("Connecting to Minecraft...")
    env = MinecraftPvPEnv()

    # Wrap with VecNormalize (must match training)
    vec_env = DummyVecEnv([lambda: env])
    vec_norm_path = "mcai_vecnormalize.pkl"
    if os.path.exists(vec_norm_path):
        print(f"Loading VecNormalize stats from {vec_norm_path}...")
        vec_env = VecNormalize.load(vec_norm_path, vec_env)
        vec_env.training = False
        vec_env.norm_reward = False
    else:
        print("WARNING: No vecnormalize stats found!")
        vec_env = VecNormalize(vec_env, norm_obs=True, norm_reward=False, clip_obs=10.0)
        vec_env.training = False

    print("Loading the trained brain (mcai_model_latest.zip)...")
    model = PPO.load("mcai_model_latest")

    print("Brain loaded! Spawning into the world...")
    
    obs = vec_env.reset()
    
    try:
        while True:
            # deterministic=True: "Stop exploring. Do exactly what you think is best."
            action, _states = model.predict(obs, deterministic=True)
            
            obs, reward, done, info = vec_env.step(action)
            
            if done[0]:
                print("Match over! Resetting...")
                obs = vec_env.reset()
                
    except KeyboardInterrupt:
        print("\nTesting stopped by user.")
    finally:
        env.close()

if __name__ == "__main__":
    main()