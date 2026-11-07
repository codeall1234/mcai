import gymnasium as gym
from gymnasium import spaces
import numpy as np
import json
import websocket
import time
from stable_baselines3 import PPO
from stable_baselines3.common.vec_env import DummyVecEnv, VecNormalize
import os

class MinecraftPvPEvalEnv(gym.Env):
    """Evaluation environment matching the expanded train.py observation/action space."""
    def __init__(self):
        super(MinecraftPvPEvalEnv, self).__init__()
        
        # Connect to the Java Mod
        self.ws = websocket.WebSocket()
        connected = False
        while not connected:
            try:
                self.ws.connect("ws://127.0.0.1:44444")
                connected = True
            except (ConnectionRefusedError, ConnectionResetError, websocket.WebSocketException):
                print("Waiting for Minecraft to start (connection refused)...")
                time.sleep(2)
        print("Connected to Minecraft Environment!")

        # Action Space: [W, S, A, D, Jump, Sprint, Attack, MouseX(7), MouseY(7)]
        self.action_space = spaces.MultiDiscrete([2, 2, 2, 2, 2, 2, 2, 7, 7])

        # Observation Space: 19 features (matches train.py)
        self.observation_space = spaces.Box(
            low=np.array([
                0.0, 0.0, 0.0, -180.0, -90.0, 0.0, 0.0,
                -1.0, -1.0, -1.0, -1.0, -1.0, 0.0, 0.0, 0.0, -20.0, 0.0, 0.0, 0.0
            ], dtype=np.float32),
            high=np.array([
                20.0, 1.0, 100.0, 180.0, 90.0, 1.0, 100.0,
                1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 20.0, 1.0, 1.0, 1.0
            ], dtype=np.float32),
            dtype=np.float32
        )

        self.previous_health = 20.0
        self.previous_distance = 100.0
        self.previous_attack = False
        self.current_cooldown = 0.0
        self.previous_enemy_health = 20.0
        self.steps_taken = 0
        self.max_steps = 1200
        self.episode_hits = 0
        self.consecutive_hits = 0
        self.current_yaw_speed = 0.0
        self.current_pitch_speed = 0.0
        
        # Win/loss tracking
        self.wins = 0
        self.losses = 0
        self.draws = 0

    def _parse_state(self, state_data):
        health = float(state_data.get('health', 20.0))
        cooldown = float(state_data.get('cooldown', 0.0))
        distance = float(state_data.get('distanceToOpponent', 100.0))
        yaw_diff = float(state_data.get('yawDiff', 0.0))
        pitch_diff = float(state_data.get('pitchDiff', 0.0))
        targeting = bool(state_data.get('targetingEntity', False))
        enemy_health = float(state_data.get('enemyHealth', 20.0))
        vel_x = float(state_data.get('velX', 0.0))
        vel_y = float(state_data.get('velY', 0.0))
        vel_z = float(state_data.get('velZ', 0.0))
        enemy_approach = float(state_data.get('enemyApproachSpeed', 0.0))
        enemy_lateral = float(state_data.get('enemyLateralSpeed', 0.0))
        sprinting = bool(state_data.get('isSprinting', False))
        on_ground = bool(state_data.get('isOnGround', True))
        enemy_sprinting = bool(state_data.get('enemySprinting', False))
        food_level = float(state_data.get('foodLevel', 20.0))
        return (health, cooldown, distance, yaw_diff, pitch_diff, targeting,
                enemy_health, vel_x, vel_y, vel_z, enemy_approach, enemy_lateral,
                sprinting, on_ground, enemy_sprinting, food_level)

    def _build_obs(self, health, cooldown, distance, yaw_diff, pitch_diff,
                   targeting, enemy_health, vel_x, vel_y, vel_z, enemy_approach,
                   enemy_lateral, sprinting, on_ground, enemy_sprinting,
                   food_level, was_attacking):
        norm_vel_x = np.clip(vel_x / 0.3, -1.0, 1.0)
        norm_vel_y = np.clip(vel_y / 0.5, -1.0, 1.0)
        norm_vel_z = np.clip(vel_z / 0.3, -1.0, 1.0)
        norm_approach = np.clip(enemy_approach / 0.5, -1.0, 1.0)
        norm_lateral = np.clip(enemy_lateral / 0.5, -1.0, 1.0)
        norm_food = np.clip(food_level / 20.0, 0.0, 1.0)
        health_advantage = health - enemy_health
        steps_remaining = 1.0 - (self.steps_taken / self.max_steps)

        return np.array([
            health, cooldown, distance, yaw_diff, pitch_diff,
            float(targeting), enemy_health,
            norm_vel_x, norm_vel_y, norm_vel_z, norm_approach, norm_lateral,
            float(sprinting), float(on_ground), float(was_attacking),
            health_advantage, steps_remaining, float(enemy_sprinting),
            norm_food
        ], dtype=np.float32)

    def step(self, action):
        self.steps_taken += 1

        # Smooth mouse: 7 options → {-3,-2,-1,0,+1,+2,+3}
        raw_yaw = float((action[7] - 3) * 6.0)
        raw_pitch = float((action[8] - 3) * 3.0)

        if action[7] == 3:
            self.current_yaw_speed *= 0.5
        else:
            self.current_yaw_speed += (raw_yaw - self.current_yaw_speed) * 0.25

        if action[8] == 3:
            self.current_pitch_speed *= 0.5
        else:
            self.current_pitch_speed += (raw_pitch - self.current_pitch_speed) * 0.25

        mc_action = {
            "forward": bool(action[0]),
            "backward": bool(action[1]),
            "left": bool(action[2]),
            "right": bool(action[3]),
            "jump": bool(action[4]),
            "sneak": False,
            "sprint": bool(action[5]),
            "attack": bool(action[6]),
            "deltaYaw": self.current_yaw_speed,
            "deltaPitch": self.current_pitch_speed
        }

        ai_wanted_attack = mc_action["attack"]
        if self.current_cooldown > 0.05:
            mc_action["attack"] = False

        self.ws.send(json.dumps(mc_action))
        state_str = self.ws.recv()
        state_data = json.loads(state_str)

        parsed = self._parse_state(state_data)
        (current_health, self.current_cooldown, distance, yaw_diff, pitch_diff,
         targeting, enemy_health, vel_x, vel_y, vel_z, approach, lateral,
         sprinting, on_ground, enemy_sprinting, food_level) = parsed

        observation = self._build_obs(
            current_health, self.current_cooldown, distance, yaw_diff, pitch_diff,
            targeting, enemy_health, vel_x, vel_y, vel_z, approach, lateral,
            sprinting, on_ground, enemy_sprinting, food_level, ai_wanted_attack
        )

        # Simple reward for eval logging
        reward = 0.0
        
        # Track damage dealt
        if enemy_health < self.previous_enemy_health:
            damage_dealt = self.previous_enemy_health - enemy_health
            self.episode_hits += 1
            self.consecutive_hits += 1
            reward += damage_dealt * 8.0
            if self.episode_hits % 5 == 0:
                print(f"  [HIT x{self.episode_hits}] Combo: {self.consecutive_hits} | Dmg: {damage_dealt:.1f}")

        if current_health < self.previous_health:
            self.consecutive_hits = 0
            damage_taken = self.previous_health - current_health
            reward -= damage_taken * 4.0

        terminated = current_health <= 0.0 or enemy_health <= 0.0
        truncated = self.steps_taken >= self.max_steps

        if enemy_health <= 0.0 and self.previous_enemy_health > 0:
            self.wins += 1
            wr = self.wins / max(1, self.wins + self.losses + self.draws) * 100
            print(f"\n>>> [VICTORY] Hits: {self.episode_hits} | Record: {self.wins}W-{self.losses}L-{self.draws}D ({wr:.0f}% WR) <<<\n")
        elif current_health <= 0.0 and self.previous_health > 0:
            self.losses += 1
            wr = self.wins / max(1, self.wins + self.losses + self.draws) * 100
            print(f"\n>>> [DEFEAT] Hits: {self.episode_hits} | Record: {self.wins}W-{self.losses}L-{self.draws}D ({wr:.0f}% WR) <<<\n")
        elif truncated:
            self.draws += 1
            print(f"\n[TIMEOUT] Hits: {self.episode_hits} | HP: {current_health:.1f} vs {enemy_health:.1f}\n")

        self.previous_health = current_health
        self.previous_distance = distance
        self.previous_attack = ai_wanted_attack
        self.previous_enemy_health = enemy_health

        return observation, reward, terminated, truncated, {}

    def reset(self, seed=None, options=None):
        super().reset(seed=seed)
        self.steps_taken = 0
        self.previous_attack = False
        self.current_cooldown = 0.0
        self.episode_hits = 0
        self.consecutive_hits = 0
        self.current_yaw_speed = 0.0
        self.current_pitch_speed = 0.0

        state_str = self.ws.recv()
        state_data = json.loads(state_str)
        parsed = self._parse_state(state_data)
        (health, cooldown, dist, yd, pd, tgt, eh,
         vx, vy, vz, ap, lt, sp, gr, esp, food) = parsed

        obs = self._build_obs(health, cooldown, dist, yd, pd, tgt, eh,
                              vx, vy, vz, ap, lt, sp, gr, esp, food, False)
        
        self.previous_health = health
        self.previous_distance = dist
        self.previous_enemy_health = eh
        return obs, {}

    def close(self):
        if hasattr(self, 'ws') and self.ws:
            self.ws.close()
            print("WebSocket connection closed.")

# -----------------------------------------------------------------
# START THE EVALUATION ENGINE
# -----------------------------------------------------------------
if __name__ == "__main__":
    print("Setting up the AI Environment for Evaluation...")
    env = MinecraftPvPEvalEnv()
    
    # Wrap in VecNormalize with saved stats (must match training normalization)
    vec_env = DummyVecEnv([lambda: env])
    vec_norm_path = "mcai_vecnormalize.pkl"
    if os.path.exists(vec_norm_path):
        print(f"Loading VecNormalize stats from {vec_norm_path}...")
        vec_env = VecNormalize.load(vec_norm_path, vec_env)
        vec_env.training = False  # Don't update stats during eval
        vec_env.norm_reward = False
    else:
        print("WARNING: No vecnormalize stats found. Evaluation may behave differently than training.")
        vec_env = VecNormalize(vec_env, norm_obs=True, norm_reward=False, clip_obs=10.0)
        vec_env.training = False

    print("Loading trained model...")
    try:
        model = PPO.load("mcai_model_latest")
        print("Model loaded successfully!")
        print(f"  Network: {model.policy}")
    except Exception as e:
        print(f"Error loading model: {e}")
        print("Make sure 'mcai_model_latest.zip' exists in the current directory.")
        exit(1)

    print("Starting Evaluation! Look at Minecraft!")
    obs = vec_env.reset()
    try:
        while True:
            action, _states = model.predict(obs, deterministic=True)
            obs, reward, done, info = vec_env.step(action)
            
            if done[0]:
                print("Episode finished! Resetting...")
                obs = vec_env.reset()
    except KeyboardInterrupt:
        print("\nEvaluation stopped by user.")
    finally:
        env.close()