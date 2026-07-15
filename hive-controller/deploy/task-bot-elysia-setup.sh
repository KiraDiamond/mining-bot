#!/usr/bin/env bash
set -euo pipefail

sudo pacman -Syu --needed nodejs npm git rsync prismlauncher jdk25-openjdk xorg-server-xvfb mesa gradle

cd "$HOME/afk-farm-manager"
npm ci

if [[ ! -f .env ]]; then
  cp .env.example .env
  python - <<'PY'
from pathlib import Path
import secrets
path = Path(".env")
text = path.read_text()
text = text.replace("replace-with-random-shared-token", secrets.token_urlsafe(32))
path.write_text(text)
PY
  echo "Created .env. Edit DISCORD_TOKEN, DISCORD_CLIENT_ID, and OWNER_IDS before starting the service."
fi

mkdir -p "$HOME/.config/systemd/user"
cp deploy/afk-discord.service "$HOME/.config/systemd/user/"
systemctl --user daemon-reload

cat <<'EOF'
Base packages are installed.

Next:
1. Put DISCORD_TOKEN, DISCORD_CLIENT_ID, OWNER_IDS, and TASK_CONTROL_TOKEN in ~/afk-farm-manager/.env.
2. Run: npm run register
3. Start service: systemctl --user enable --now afk-discord.service
4. Open PrismLauncher on elysia once and log in each Microsoft account/profile.
5. Build the Fabric client after copying Baritone into task-client-fabric/libs/ and providing a 26.2 mapping jar with a named namespace:
   mkdir -p task-client-fabric/libs
   cp /path/to/baritone-meteor-26.2-local.jar task-client-fabric/libs/
   cd task-client-fabric && gradle build
6. Put task-client-fabric/build/libs/hive-task-client-0.1.0.jar plus Baritone and Fabric API into each task-bot instance mods folder.
EOF
