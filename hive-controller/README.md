# AFK Farm Manager

Headless Minecraft AFK account launcher. For current `26.2` servers it can use Botcraft, because `minecraft-protocol` does not currently have usable generated protocol data for `26.2`.

## Start

```powershell
npm start
```

You can also run one command directly:

```powershell
node src/index.js afkset iron --account myalt@example.com --host play.example.net --port 25565 --version auto
node src/index.js afk iron
```

## Commands

```text
/afkset iron --account myalt@example.com --host play.example.net --port 25565 --version auto
/afk iron
/farms
/stop iron
/stopall
/quit
```

If you omit options, `/afkset iron` asks for them interactively.

Microsoft accounts use device-code login on first use. Tokens are cached in `data/auth/`; do not share that folder.

For Botcraft mode, each farm gets its own auth cache under:

```text
data/botcraft/<farm>/
```

## Discord Bot

This can also run as a Discord user-installable app with slash commands.

Commands:

```text
/claim-owner
/afkset farm:iron account:myalt@example.com host:play.example.net port:25565 version:auto auth:microsoft
/afk farm:iron
/afkall
/farms
/stop farm:iron
/stopall
/remove farm:iron
/owners
/taskbotset name:astral account:alt@example.com server:server.address port:25565
/tasklogin name:astral
/tasklaunch name:astral
/taskbots
/nozone add name:base x1:-100 y1:-64 z1:-100 x2:100 y2:320 z2:100
/taskmine resource item:oak_log amount:256 bots:all
/taskmine cuboid x1:0 y1:60 z1:0 x2:15 y2:80 z2:15 bots:all
/taskstatus
/taskstop bots:all
```

The first owner can run `/claim-owner`. After that, only owners can control accounts. You can also set `OWNER_IDS` in `.env` to a comma-separated list of Discord user IDs.

### Discord App Setup

In the Discord Developer Portal:

1. Reset the bot token and put the new token in `.env`.
2. Copy the Application ID and put it in `DISCORD_CLIENT_ID`.
3. Enable the install contexts you want for the app. The command registration uses both guild install and user install.
4. Run `npm run register` once after changing commands.
5. Run `npm run bot` to start the Discord control process.

`.env`:

```env
DISCORD_TOKEN=your-new-token
DISCORD_CLIENT_ID=your-application-id
OWNER_IDS=
AFK_BACKEND=botcraft
BOTCRAFT_AFK_BIN=/home/diamond/botcraft-26.2/botcraft/bin/3_SimpleAFKExample
TASK_CONTROL_HOST=127.0.0.1
TASK_CONTROL_PORT=47391
TASK_CONTROL_TOKEN=replace-with-random-shared-token
XVFB_RUN_BIN=xvfb-run
TASK_CLIENT_COMMAND=
```

## Task Bots

Task bots are separate from the AFK Botcraft accounts. The controller no longer needs Prism. Run the terminal console and point each bot at a direct launch command, for example a wrapper script that starts a Fabric/Baritone client under `xvfb-run`.

```bash
npm run task-console
```

Console commands:

```text
bot set kira kira@example.com 209.25.141.24 1306 --cmd "xvfb-run -a ./launch-task-client.sh --server {server}"
bot list
launch kira
status
goto kira 22 63 -192
clear -113 63 14 -240 110 -250 all
mine oak_log 256 all
stop all
kill kira
quit
```

Launch command placeholders:

```text
{bot} {account} {host} {port} {server} {controlHost} {controlPort} {token}
```

The launch command also receives these environment variables:

```env
TASK_BOT_ID=kira
TASK_CONTROL_HOST=127.0.0.1
TASK_CONTROL_PORT=47391
TASK_CONTROL_TOKEN=the-shared-token
TASK_SERVER_HOST=209.25.141.24
TASK_SERVER_PORT=1306
TASK_SERVER=209.25.141.24:1306
TASK_ACCOUNT=kira@example.com
```

The console starts a localhost JSONL control server. The Fabric/control adapter reads:

```env
TASK_BOT_ID=astral
TASK_CONTROL_HOST=127.0.0.1
TASK_CONTROL_PORT=47391
TASK_CONTROL_TOKEN=the-same-token-as-the-discord-controller
```

Protect base/no-mine areas before mining:

```text
nozone add base -200 -64 -200 200 320 200
```

Resource mining currently supports `oak_log` as the first seed-planned resource. The planner uses the configured seed `4145193711797456157` and avoids no-mine zones, but the first target should still be verified in-game before large jobs because the controller does not embed the full Minecraft biome generator.

```text
mine oak_log 256 all
```

Cuboid mining is split by Y layer and executed through Baritone `sel pos1`, `sel pos2`, and `sel ca`:

```text
clear 100 50 100 130 80 130 all
```

The client mod stops if the selected tool or elytra reaches `5` durability and no safer hotbar item is available.

### CachyOS Deploy

On `elysia`:

```bash
sudo pacman -Syu --needed nodejs npm git rsync
mkdir -p ~/afk-farm-manager
mkdir -p ~/botcraft-26.2
cd ~/botcraft-26.2
curl -fL -o botcraft-linux-26.2.zip https://github.com/adepierre/Botcraft/releases/download/latest/botcraft-linux-26.2.zip
unzip -o botcraft-linux-26.2.zip
chmod +x ~/botcraft-26.2/botcraft/bin/*
```

From the Windows machine, once SSH works:

```powershell
cd "C:\Users\jbisb\Documents\New project 2"
rsync -av --delete --exclude node_modules --exclude data afk-farm-manager/ diamond@192.168.0.92:~/afk-farm-manager/
```

Then on `elysia`:

```bash
cd ~/afk-farm-manager
npm ci
cp .env.example .env
nano .env
npm run register
mkdir -p ~/.config/systemd/user
cp deploy/afk-discord.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now afk-discord.service
loginctl enable-linger "$USER"
```

For task-bot support on `elysia`, use:

```bash
cd ~/afk-farm-manager
bash deploy/task-bot-elysia-setup.sh
```

Watch logs:

```bash
journalctl --user -u afk-discord.service -f
```

### Adding Accounts

Add a farm profile from Discord:

```text
/afkset farm:iron account:alt-email@example.com host:server.address port:25565 version:auto auth:microsoft
```

Start it:

```text
/afk farm:iron
```

On first start, Microsoft device-code login appears in Discord as an ephemeral follow-up and in the systemd logs. Complete the browser login once. The auth cache is then stored under:

```text
~/afk-farm-manager/data/auth/
```

To add another account, make another farm with a different `account` value:

```text
/afkset farm:gold account:second-alt@example.com host:server.address port:25565 version:auto auth:microsoft
/afk farm:gold
```

Do not share `data/auth/`; those files are the sign-in cache.

## Notes

- This is lighter than a modded client because it does not render, simulate Baritone, or load a world client.
- Botcraft is currently used for `26.2` because it has a release built for that Minecraft version.
- If you switch back to `minecraft-protocol`, unsupported protocol versions will not join until that dependency updates.
- Use only on servers where your account automation is allowed.
