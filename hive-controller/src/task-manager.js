const path = require('path');
const os = require('os');
const { promisify } = require('util');
const { spawn, execFile } = require('child_process');
const crypto = require('crypto');
const fs = require('fs/promises');
let Authflow;
let Titles;
try {
  ({ Authflow, Titles } = require('prismarine-auth'));
} catch {}
const { TaskControlServer } = require('./task-control-server');
const { loadTaskStore, saveTaskStore } = require('./task-store');
const { dataDir } = require('./task-store');
const {
  DEFAULT_WORLD_SEED,
  clean,
  safeName,
  normalizeCuboid,
  assertOutsideNoZones,
  splitCuboidByY,
  splitCuboidByX,
  splitCuboidByLongestHorizontal,
  normalizeResource,
  planResourceTarget,
  formatCuboid
} = require('./task-utils');

const DEFAULT_LAUNCH_TIMEOUT_MS = 45000;
const NATIVE_COMMAND_FILE = '/tmp/taskbot-native-command.txt';
const NATIVE_SAFE_BARITONE_SETTINGS = [
  'blockReachDistance 2.75',
  'walkWhileBreaking false',
  'breakFromAbove true',
  'elytraAutoJump true',
  'elytraMinimumDurability 10',
  'elytraMinFireworksBeforeLanding 3',
  'elytraTermsAccepted true'
].join('\n') + '\n';
const NATIVE_PROCESS_SCAN = `
for proc in /proc/[0-9]*/cmdline; do
  pid=$(basename "$(dirname "$proc")")
  [ "$pid" = "$$" ] && continue
  cmd=$(tr '\\0' ' ' < "$proc" 2>/dev/null || true)
  case "$cmd" in
    *baritone-26.2-native*|*'/home/diamond/baritone-task-client/prism'*|*org.prismlauncher.EntryPoint*|*net.minecraft.client.main.Main*) printf '%s %s\\n' "$pid" "$cmd" ;;
  esac
done
`;
const execFileAsync = promisify(execFile);

function nativeFilePart(name) {
  return safeName(name) || 'kira';
}

function nativeCommandFile(name) {
  const botName = nativeFilePart(name);
  return botName === 'kira' ? NATIVE_COMMAND_FILE : `/tmp/taskbot-native-${botName}-command.txt`;
}

function nativeTaskStateFile(name) {
  const botName = nativeFilePart(name);
  return path.join(dataDir, `native-task-${botName}-active.json`);
}

function shellSingleQuote(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

function nativeProcessScan(instanceId) {
  const pattern = shellSingleQuote(instanceId);
  return `
needle=${pattern}
for proc in /proc/[0-9]*/cmdline; do
  pid=$(basename "$(dirname "$proc")")
  [ "$pid" = "$$" ] && continue
  cmd=$(tr '\\0' ' ' < "$proc" 2>/dev/null || true)
  case "$cmd" in
    *prismlauncher*"--launch $needle"*|*"/instances/$needle/"*) printf '%s %s\\n' "$pid" "$cmd" ;;
  esac
done
`;
}

function parseCommandLine(value) {
  const input = clean(value);
  const args = [];
  let current = '';
  let quote = null;
  let escape = false;

  for (const char of input) {
    if (escape) {
      current += char;
      escape = false;
      continue;
    }
    if (char === '\\') {
      escape = true;
      continue;
    }
    if (quote) {
      if (char === quote) quote = null;
      else current += char;
      continue;
    }
    if (char === '"' || char === "'") {
      quote = char;
      continue;
    }
    if (/\s/.test(char)) {
      if (current) {
        args.push(current);
        current = '';
      }
      continue;
    }
    current += char;
  }

  if (escape) current += '\\';
  if (quote) throw new Error('Launch command has an unterminated quote.');
  if (current) args.push(current);
  return args;
}

function fillTemplate(value, replacements) {
  return clean(value).replace(/\{([a-zA-Z0-9_]+)\}/g, (match, key) => (
    Object.prototype.hasOwnProperty.call(replacements, key) ? String(replacements[key]) : match
  ));
}

class TaskManager {
  constructor({ discordClient = null } = {}) {
    this.discordClient = discordClient;
    this.launches = new Map();
    this.activeTasks = new Map();
    this.botDiagnostics = new Map();
    this.pendingResumeChecks = new Set();
    this.taskReconnectTimers = new Map();
    this.taskReconnectAttempts = new Map();
    this.ready = false;
    this.control = new TaskControlServer({
      host: process.env.TASK_CONTROL_HOST || '127.0.0.1',
      port: Number.parseInt(process.env.TASK_CONTROL_PORT || '47391', 10),
      token: process.env.TASK_CONTROL_TOKEN || 'dev-task-token'
    });
    this.control.on('connect', (event) => this.#handleBotConnect(event));
    this.control.on('disconnect', (event) => this.#handleBotDisconnect(event));
    this.control.on('message', (event) => this.#handleBotMessage(event));
  }

  async start() {
    if (this.ready) return;
    await this.control.start();
    this.ready = true;
    console.log(`Task control server listening on ${this.control.host}:${this.control.port}`);
    if (!/^(false|0|no)$/i.test(clean(process.env.TASK_AUTO_RESUME || 'true'))) {
      void this.#resumePersistedManagedBots()
        .catch((error) => console.error(`Failed to auto-resume managed task bots: ${error.message}`));
    }
  }

  async stop() {
    for (const name of this.launches.keys()) this.stopLaunch(name);
    for (const timer of this.taskReconnectTimers.values()) clearTimeout(timer);
    this.taskReconnectTimers.clear();
    await this.control.stop();
  }

  async setBot(input) {
    const name = safeName(input.name);
    if (!name) throw new Error('Bot name is required.');
    const account = clean(input.account);
    const host = clean(input.host);
    const port = Number.parseInt(input.port || 25565, 10);
    if (!account || !host) throw new Error('account and host are required.');
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('port must be 1-65535.');

    const store = await loadTaskStore();
    const existing = store.bots[name] || {};
    const launchCommand = clean(input.launchCommand || input.command || input.cmd || existing.launchCommand || process.env.TASK_CLIENT_COMMAND);
    const profile = clean(input.profile || existing.profile);
    const instanceId = clean(input.instanceId || input.instance || existing.instanceId || existing.nativeInstance);
    store.bots[name] = {
      ...existing,
      name,
      account,
      auth: 'microsoft',
      host,
      port,
      launchCommand,
      profile,
      instanceId,
      updatedAt: new Date().toISOString(),
      createdAt: existing.createdAt || new Date().toISOString()
    };
    await saveTaskStore(store);
    return store.bots[name];
  }

  async authenticateBot(name, onCode = () => {}) {
    if (!Authflow || !Titles) throw new Error('prismarine-auth is not available.');
    const botName = safeName(name);
    const store = await loadTaskStore();
    const bot = store.bots[botName];
    if (!bot) throw new Error(`No task bot named "${botName}".`);
    if (!clean(bot.account)) throw new Error(`Task bot "${botName}" has no account email saved.`);

    const profilesFolder = path.join(dataDir, 'task-auth', clean(bot.account).replace(/[^a-zA-Z0-9._-]+/g, '_').slice(0, 80));
    if (bot.authFailedAt) {
      await fs.rm(profilesFolder, { recursive: true, force: true });
    }
    let codeSent = false;
    const flow = new Authflow(
      bot.account,
      profilesFolder,
      { flow: 'live', authTitle: Titles.MinecraftNintendoSwitch, deviceType: 'Nintendo' },
      (data) => {
        if (codeSent) return;
        codeSent = true;
        onCode(data);
      }
    );

    let token;
    try {
      token = await Promise.race([
        flow.getMinecraftJavaToken({ fetchProfile: true }),
        new Promise((_, reject) => setTimeout(() => reject(new Error('Timed out waiting for Microsoft sign-in. Run `/tasklogin` to retry.')), 180000))
      ]);
    } catch (error) {
      const failed = await loadTaskStore();
      if (failed.bots[botName]) {
        failed.bots[botName].authFailedAt = new Date().toISOString();
        failed.bots[botName].authError = clean(error.message).slice(0, 500);
        await saveTaskStore(failed);
      }
      throw error;
    }

    const displayName = clean(token?.profile?.name || token?.selectedProfile?.name || token?.name || token?.username);
    const updated = await loadTaskStore();
    if (updated.bots[botName]) {
      updated.bots[botName].displayName = displayName || updated.bots[botName].displayName;
      updated.bots[botName].profile = displayName || updated.bots[botName].profile || updated.bots[botName].account;
      updated.bots[botName].authCachedAt = new Date().toISOString();
      delete updated.bots[botName].authFailedAt;
      delete updated.bots[botName].authError;
      await saveTaskStore(updated);
    }
    return { botName, displayName, codeSent };
  }

  async listBots() {
    const store = await loadTaskStore();
    const connected = new Map(this.control.listClients().map((client) => [client.botId, client]));
    return Object.values(store.bots).map((bot) => ({
      ...bot,
      launched: this.launches.has(bot.name),
      connected: connected.has(bot.name) || this.launches.has(bot.name),
      username: connected.get(bot.name)?.username || this.launches.get(bot.name)?.username || bot.displayName || bot.name,
      status: connected.get(bot.name)?.status || null,
      backend: this.launches.get(bot.name)?.backend || (bot.launchCommand ? 'command' : 'unconfigured')
    }));
  }

  async launchBot(name, events = {}) {
    const botName = safeName(name);
    if (this.launches.has(botName)) return { ok: false, message: `\`${botName}\` is already launched.` };

    const store = await loadTaskStore();
    const bot = store.bots[botName];
    if (!bot) throw new Error(`No task bot named "${botName}".`);

    const server = `${bot.host}:${bot.port || 25565}`;
    const launchCommand = clean(bot.launchCommand || process.env.TASK_CLIENT_COMMAND);
    if (!launchCommand) {
      throw new Error(`Task bot "${botName}" has no launch command. Set one with "bot set ... --cmd <command>" or TASK_CLIENT_COMMAND.`);
    }

    const replacements = {
      bot: botName,
      name: botName,
      account: bot.account,
      host: bot.host,
      port: bot.port || 25565,
      server,
      controlHost: this.control.host,
      controlPort: this.control.port,
      token: this.control.token
    };
    const commandParts = parseCommandLine(fillTemplate(launchCommand, replacements));
    if (!commandParts.length) throw new Error(`Task bot "${botName}" launch command is empty.`);
    const [command, ...args] = commandParts;
    const cwd = clean(bot.cwd || process.env.TASK_CLIENT_CWD || process.cwd());

    const child = spawn(command, args, {
      cwd,
      stdio: ['ignore', 'pipe', 'pipe'],
      shell: process.platform === 'win32',
      env: {
        ...process.env,
        TASK_CONTROL_HOST: this.control.host,
        TASK_CONTROL_PORT: String(this.control.port),
        TASK_CONTROL_TOKEN: this.control.token,
        TASK_BOT_ID: botName,
        TASK_SERVER_HOST: bot.host,
        TASK_SERVER_PORT: String(bot.port || 25565),
        TASK_SERVER: server,
        TASK_ACCOUNT: bot.account
      }
    });

    const session = {
      child,
      bot,
      backend: 'command',
      startedAt: new Date(),
      lastOutput: '',
      server,
      command,
      args,
      cwd,
      username: bot.displayName || botName,
      notifiedLogin: false
    };
    this.launches.set(botName, session);

    const relay = (chunk, isError = false) => {
      const text = chunk.toString().trim();
      if (!text) return;
      session.lastOutput = text.slice(-1000);
      console[isError ? 'error' : 'log'](`[task:${botName}] ${text}`);
      for (const line of text.split(/\r?\n/).map((value) => value.trim()).filter(Boolean)) this.#handleLauncherLine(botName, session, line, events);
    };
    child.stdout.on('data', (chunk) => relay(chunk));
    child.stderr.on('data', (chunk) => relay(chunk, true));
    child.once('exit', (code, signal) => {
      this.launches.delete(botName);
      console.log(`[task:${botName}] launch command exited code=${code ?? ''} signal=${signal || ''}`);
      if (events.onEvent) {
        events.onEvent(`\`${session.username || botName}\` launch command exited code=${code ?? ''} signal=${signal || ''}.`);
      }
    });

    const connected = await this.#waitForControlConnection(botName, DEFAULT_LAUNCH_TIMEOUT_MS).catch(() => false);
    return {
      ok: true,
      message: connected
        ? `Launched \`${botName}\` with command -> \`${server}\` and connected to controller.`
        : `Started launch command for \`${botName}\` -> \`${server}\`; waiting for client to connect to controller.`
    };
  }

  stopLaunch(name) {
    const botName = safeName(name);
    const session = this.launches.get(botName);
    if (!session) return false;
    try {
      this.control.send(botName, { type: 'stop', id: crypto.randomUUID() });
    } catch {}
    if (session.child && !session.child.killed) session.child.kill('SIGTERM');
    this.launches.delete(botName);
    return true;
  }

  async gotoBot(name, x, y, z, speed = 1) {
    const botName = this.#resolveLaunchedBotName(name);
    const session = this.launches.get(botName);
    if (!session) throw new Error('No launched task bot matched. Use `/tasklaunch` first.');
    const coords = [x, y, z].map((value) => Number.parseInt(value, 10));
    if (coords.some((value) => !Number.isFinite(value))) throw new Error('x, y, and z must be valid integers.');
    const speedNumber = Number.parseFloat(speed || 1);
    if (!Number.isFinite(speedNumber) || speedNumber <= 0 || speedNumber > 5) throw new Error('speed must be between 0 and 5.');
    if (session.backend === 'botcraft') {
      await this.#appendBotcraftCommand(botName, `goto ${coords[0]} ${coords[1]} ${coords[2]} ${speedNumber}`);
    } else {
      this.control.send(botName, {
        type: 'task',
        task: {
          id: crypto.randomUUID(),
          kind: 'goto',
          x: coords[0],
          y: coords[1],
          z: coords[2],
          minDurability: 5
        }
      });
    }
    return {
      botName,
      username: session.username || botName,
      message: `Sent \`${session.username || botName}\` to ${coords[0]} ${coords[1]} ${coords[2]}.`
    };
  }

  async addNoZone(input) {
    const name = safeName(input.name);
    if (!name) throw new Error('Zone name is required.');
    const cuboid = normalizeCuboid(input);
    const store = await loadTaskStore();
    const existing = store.noZones.find((zone) => zone.name === name);
    if (existing) {
      existing.cuboid = cuboid;
      existing.disabled = false;
      existing.updatedAt = new Date().toISOString();
    } else {
      store.noZones.push({ name, cuboid, disabled: false, createdAt: new Date().toISOString() });
    }
    await saveTaskStore(store);
    return { name, cuboid };
  }

  async archiveNoZone(name) {
    const zoneName = safeName(name);
    const store = await loadTaskStore();
    const zone = store.noZones.find((entry) => entry.name === zoneName && !entry.disabled);
    if (!zone) return false;
    zone.disabled = true;
    zone.disabledAt = new Date().toISOString();
    await saveTaskStore(store);
    return true;
  }

  async listNoZones({ includeDisabled = false } = {}) {
    const store = await loadTaskStore();
    return store.noZones.filter((zone) => includeDisabled || !zone.disabled);
  }

  async mineCuboid(input) {
    const store = await loadTaskStore();
    const cuboid = normalizeCuboid(input);
    assertOutsideNoZones(cuboid, store.noZones);
    const bots = this.#resolveConfiguredTaskBots(input.bots || 'all', store);
    const botcraftBots = bots.filter((bot) => this.launches.get(bot)?.backend === 'botcraft');
    if (botcraftBots.length === bots.length) {
      const slices = splitCuboidByX(cuboid, bots);
      const taskId = crypto.randomUUID();
      for (const slice of slices) {
        this.activeTasks.set(slice.bot, { taskId, kind: 'clear-cuboid', cuboid: slice.cuboid });
        await this.#appendBotcraftCommand(
          slice.bot,
          `clear_cuboid ${slice.cuboid.x1} ${slice.cuboid.y1} ${slice.cuboid.z1} ${slice.cuboid.x2} ${slice.cuboid.y2} ${slice.cuboid.z2} 2048`
        );
      }
      return {
        taskId,
        message: `Started Botcraft cuboid clear \`${taskId}\`: ${slices.map((s) => `\`${s.bot}\` ${formatCuboid(s.cuboid)}`).join('; ')}.`
      };
    }

    const slices = splitCuboidByLongestHorizontal(cuboid, bots);
    const taskId = crypto.randomUUID();
    const minDurability = Math.max(10, store.settings.minDurability || 10);
    for (const slice of slices) {
      await this.ensureNativeTaskClient(slice.bot);
      await this.#waitForControlConnection(slice.bot, 60000).catch(() => {
        throw new Error(`Native task bot "${slice.bot}" joined Minecraft but did not connect to the managed controller.`);
      });
      const payload = {
        id: taskId,
        kind: 'mine-cuboid',
        cuboid: slice.cuboid,
        originalCuboid: cuboid,
        noZones: store.noZones.filter((zone) => !zone.disabled),
        minDurability
      };
      const active = {
        taskId,
        kind: 'mine-cuboid',
        cuboid: slice.cuboid,
        payload,
        active: true,
        notifyChannelId: input.notifyChannelId || null,
        notifyUserId: input.notifyUserId || null
      };
      this.activeTasks.set(slice.bot, active);
      this.control.send(slice.bot, { type: 'task', task: payload });
      void fs.writeFile(nativeTaskStateFile(slice.bot), JSON.stringify({ bot: slice.bot, task: active }, null, 2), 'utf8')
        .catch((error) => console.error(`Failed to persist native task state: ${error.message}`));
    }

    return {
      taskId,
      message: `Started managed snapshot mining task \`${taskId}\` with ${slices.length} bot(s): ${slices.map((s) => `\`${s.bot}\` ${formatCuboid(s.cuboid)}`).join('; ')}.`
    };
  }

  async mineResource(input) {
    const store = await loadTaskStore();
    const bots = this.#resolveConnectedBots(input.bots || 'all');
    const amount = Number.parseInt(input.amount, 10);
    const resource = normalizeResource(input.resource);
    const botcraftBots = bots.filter((bot) => this.launches.get(bot)?.backend === 'botcraft');
    if (botcraftBots.length === bots.length) {
      if (!Number.isInteger(amount) || amount < 1) throw new Error('amount must be at least 1.');
      const taskId = crypto.randomUUID();
      const requestedPerBot = Math.ceil(amount / bots.length);
      const perBot = Math.min(64, requestedPerBot);
      const radius = 8;
      for (const bot of bots) {
        this.activeTasks.set(bot, { taskId, kind: 'mine-resource-near', resource, amount: perBot });
        await this.#appendBotcraftCommand(bot, `mine_near ${resource} ${perBot} ${radius}`);
      }
      return {
        taskId,
        message: `Started safe nearby mining task \`${taskId}\`: ${bots.map((bot) => `\`${bot}\``).join(', ')} mining \`${resource}\` (${perBot}/bot, 8-block reach). Place the bot beside the tree; this mode does not climb/pathfind while mining.`
      };
    }

    const plan = planResourceTarget({
      resource,
      amount,
      seed: store.settings.seed || DEFAULT_WORLD_SEED,
      noZones: store.noZones
    });
    const taskId = crypto.randomUUID();
    const perBot = Math.ceil(amount / bots.length);
    const minDurability = store.settings.minDurability || 5;

    for (const bot of bots) {
      this.activeTasks.set(bot, {
        taskId,
        kind: 'mine-resource',
        resource,
        amount: perBot,
        target: plan,
        notifyChannelId: input.notifyChannelId || null,
        notifyUserId: input.notifyUserId || null
      });
      this.control.send(bot, {
        type: 'task',
        task: {
          id: taskId,
          kind: 'mine-resource',
          resource,
          amount: perBot,
          target: { x: plan.x, z: plan.z, cuboid: plan.cuboid },
          noZones: store.noZones.filter((zone) => !zone.disabled),
          minDurability,
          useElytra: true
        }
      });
    }

    return {
      taskId,
      message: `Started \`${resource}\` task \`${taskId}\` for ${amount} total (${perBot}/bot) near x=${plan.x}, z=${plan.z}. ${plan.note}`
    };
  }

  stopTask(name = 'all') {
    const botIds = this.#resolveConnectedBots(name, { allowEmpty: true });
    for (const botId of botIds) {
      const active = this.activeTasks.get(botId);
      this.activeTasks.delete(botId);
      this.control.send(botId, { type: 'stop', id: crypto.randomUUID() });
      if (active) {
        const stopped = { ...active, active: false, finishedAt: new Date().toISOString(), result: 'stopped' };
        void fs.writeFile(nativeTaskStateFile(botId), JSON.stringify({ bot: botId, task: stopped }, null, 2), 'utf8')
          .catch((error) => console.error(`[task:${botId}] failed to persist stop: ${error.message}`));
      }
      if (this.launches.get(botId)?.backend === 'botcraft') {
        void this.#appendBotcraftCommand(botId, 'stop');
      }
    }
    return botIds;
  }

  taskStatus() {
    const clients = this.control.listClients();
    const botcraft = [...this.launches.entries()].map(([botId, session]) => {
      const lastLine = session.lastOutput.split(/\r?\n/).map((value) => value.trim()).filter(Boolean).slice(-1)[0];
      return `\`${botId}\` ${session.username || botId} | launched via ${session.backend || 'command'} -> ${session.server}${lastLine ? ` | ${lastLine.slice(0, 140)}` : ''}`;
    });
    const controlLines = clients.map((client) => {
      const task = this.activeTasks.get(client.botId);
      const pos = client.status?.position
        ? ` @ ${Math.round(client.status.position.x)},${Math.round(client.status.position.y)},${Math.round(client.status.position.z)}`
        : '';
      const durability = client.status?.selectedItem
        ? ` | ${client.status.selectedItem.item || 'item'} durability=${client.status.selectedItem.durabilityLeft ?? 'unknown'}`
        : '';
      const stage = client.status?.stage || (task ? 'starting' : 'idle');
      const progress = Number.isInteger(client.status?.totalCells) && client.status.totalCells > 0
        ? ` | cells=${client.status.completedCells || 0}/${client.status.totalCells} mined=${client.status.minedBlocks || 0}`
        : '';
      const blocker = client.status?.blocker ? ` | blocker=${client.status.blocker}` : '';
      return `\`${client.botId}\` ${client.username}${pos} | ${stage} | ${task ? `${task.kind} ${task.taskId}` : 'idle'}${progress}${durability}${blocker}`;
    });
    const lines = [...botcraft, ...controlLines.filter((line) => !botcraft.some((botLine) => botLine.startsWith(line.split(' ')[0])))];
    if (!lines.length) return 'No task bots launched.';
    return lines.join('\n');
  }

  async executeTaskBotPlan(plan, context = {}) {
    const messages = [];
    for (const action of plan.actions || []) {
      if (action.type === 'launch') {
        messages.push(await this.ensureNativeTaskClient(action.bot || 'kira'));
        continue;
      }
      if (action.type === 'status') {
        messages.push(await this.nativeTaskStatus());
        continue;
      }
      if (action.type === 'tool_restock') {
        messages.push(await this.nativeToolRestock(action.bot || 'kira'));
        continue;
      }
      if (action.type === 'stop') {
        const botName = action.bot || 'kira';
        this.activeTasks.delete(botName);
        if (this.control.getClient(botName)) this.control.send(botName, { type: 'stop', id: crypto.randomUUID() });
        messages.push(`Stopped managed Baritone task for \`${botName}\`.`);
        continue;
      }
      if (action.type === 'goto') {
        const botName = action.bot || 'kira';
        const payload = {
          id: crypto.randomUUID(),
          kind: 'goto',
          x: Number.parseInt(action.x, 10),
          y: Number.parseInt(action.y, 10),
          z: Number.parseInt(action.z, 10)
        };
        await this.#dispatchManagedTask(botName, payload, context);
        messages.push(`Sent \`${botName}\` managed non-destructive goto: ${action.x} ${action.y} ${action.z}.`);
        continue;
      }
      if (action.type === 'elytra_goto') {
        const botName = action.bot || 'kira';
        await this.ensureNativeTaskClient(botName);
        const commands = [
          '#set elytraTermsAccepted true',
          '#set elytraAutoJump true',
          '#set elytraMinimumDurability 10',
          '#set elytraMinFireworksBeforeLanding 5',
          '#set elytraConserveFireworks false',
          `#goal ${action.x} ${action.y} ${action.z}`,
          '#elytra'
        ];
        await this.sendNativeBaritoneCommands(commands, { botName });
        this.#rememberNativeTask(action, 'native-elytra-goto', commands, context);
        messages.push(`Started \`${botName}\` native Baritone elytra travel toward: ${action.x} ${action.y} ${action.z}. If Baritone reports Nether-only, use normal goto until the overworld flight controller is added.`);
        continue;
      }
      if (action.type === 'clear_area') {
        const botName = action.bot || 'kira';
        const result = await this.mineCuboid({
          x1: action.x1, y1: action.y1, z1: action.z1,
          x2: action.x2, y2: action.y2, z2: action.z2,
          bots: botName,
          notifyChannelId: context.notifyChannelId,
          notifyUserId: context.notifyUserId
        });
        messages.push(result.message);
        continue;
      }
      if (action.type === 'mine_resource') {
        const botName = action.bot || 'kira';
        await this.ensureNativeTaskClient(botName);
        const commands = [`#mine ${action.resource}`];
        await this.sendNativeBaritoneCommands(commands, { botName });
        this.#rememberNativeTask(action, 'native-mine', commands, context);
        messages.push(`Started \`${botName}\` native Baritone mine: ${action.resource}${action.amount ? ` target ${action.amount}` : ''}.`);
        continue;
      }
      throw new Error(`Unsupported task action "${action.type}".`);
    }
    return messages.join('\n');
  }

  #rememberNativeTask(action, kind, commands, context = {}) {
    const bot = action.bot || 'kira';
    const task = {
      taskId: crypto.randomUUID(),
      kind,
      commands: [...commands],
      notifyChannelId: context.notifyChannelId || null,
      notifyUserId: context.notifyUserId || null
    };
    this.activeTasks.set(bot, task);
    void fs.writeFile(nativeTaskStateFile(bot), JSON.stringify({ bot, task }, null, 2), 'utf8')
      .catch((error) => console.error(`Failed to persist native task state: ${error.message}`));
  }

  async #dispatchManagedTask(bot, payload, context = {}) {
    const botName = safeName(bot) || 'kira';
    await this.ensureNativeTaskClient(botName);
    await this.#waitForControlConnection(botName, 60000);
    const task = {
      taskId: payload.id,
      kind: payload.kind,
      payload,
      active: true,
      notifyChannelId: context.notifyChannelId || null,
      notifyUserId: context.notifyUserId || null
    };
    this.activeTasks.set(botName, task);
    await fs.writeFile(nativeTaskStateFile(botName), JSON.stringify({ bot: botName, task }, null, 2), 'utf8');
    this.control.send(botName, { type: 'task', task: payload });
  }

  async nativeTaskStatus() {
    const { stdout } = await execFileAsync('/usr/bin/bash', ['-lc', [
      "printf 'processes\\n'",
      `(${NATIVE_PROCESS_SCAN}) | sed -n '1,20p'`,
      "printf '\\nrecent\\n'",
      "for log in /home/diamond/baritone-task-client/prism*/instances/baritone-26.2-native*/.minecraft/logs/latest.log; do [ -f \"$log\" ] || continue; printf '\\n== %s ==\\n' \"$log\"; rg -n 'TaskBot|CHAT|Baritone|Filling|Path goes|Death position|slain|drowned|fell|doomed|Disconnect|Disconnected|ERROR|Exception' \"$log\" 2>/dev/null | tail -n 12 || true; done"
    ].join('; ')], { timeout: 15000, maxBuffer: 100000 });
    return stdout.trim().slice(-1800) || 'Native task client status unavailable.';
  }

  async nativeTaskRejoin(name = 'kira', { resume = true } = {}) {
    const botName = safeName(name) || 'kira';
    await this.ensureNativeTaskClient(botName, { forceRestart: true });
    if (!resume) return `Rejoined native Baritone task client \`${botName}\`.`;

    const active = this.activeTasks.get(botName) || await this.#loadPersistedNativeTask(botName);
    if (active?.payload) {
      await this.#waitForControlConnection(botName, 60000);
      this.control.send(botName, { type: 'task', task: active.payload });
      return `Rejoined managed Baritone task client \`${botName}\` and resumed the last task.`;
    }
    if (active?.commands?.length) {
      await this.sendNativeBaritoneCommands(active.commands, { ensureLaunch: false, botName });
      return `Rejoined native Baritone task client \`${botName}\` and resumed the last task.`;
    }
    return `Rejoined native Baritone task client \`${botName}\`. No saved native task was found to resume.`;
  }

  async resetNativeTaskBot(name = 'kira', { resume = true } = {}) {
    const botName = safeName(name) || 'kira';
    const config = await this.#nativeLaunchConfig(botName);
    await this.#ensureNativePrismRoot(config);
    await this.#ensureNativeInstance(config);
    await this.#resetNativeRuntimeState(config);

    const launchMessage = await this.ensureNativeTaskClient(botName, { forceRestart: true });
    await this.#waitForNativeTaskBotReady(config);
    const active = this.activeTasks.get(botName) || await this.#loadPersistedNativeTask(botName);
    if (resume && active?.payload) {
      await this.#waitForControlConnection(botName, 60000);
      this.control.send(botName, { type: 'task', task: active.payload });
      return `${launchMessage}\nReset the client and resumed managed task \`${active.taskId || 'saved'}\` for \`${botName}\`.`;
    }
    if (resume && active?.commands?.length) {
      const resetCommands = [
        '#stop',
        '#set buildOnlySelection false',
        '#set buildInLayers false',
        '#set breakFromAbove true',
        ...active.commands.filter((command) => clean(command).toLowerCase() !== '#stop')
      ];
      await this.sendNativeBaritoneCommands(resetCommands, { ensureLaunch: false, botName });
      return `${launchMessage}\nReset Baritone state and resumed \`${botName}\` task \`${active.taskId || 'saved'}\`.`;
    }
    return `${launchMessage}\nReset Baritone state for \`${botName}\`. No saved task was found to resume.`;
  }

  async resetNativeTaskBots({ names = null, resume = true } = {}) {
    const store = await loadTaskStore();
    const botNames = (names && names.length ? names : ['kira', 'azure'])
      .map((name) => safeName(name))
      .filter(Boolean)
      .filter((name, index, list) => list.indexOf(name) === index);
    const existing = botNames.filter((name) => store.bots[name]);
    if (!existing.length) throw new Error(`No matching task bot(s): ${botNames.join(', ') || 'none'}.`);

    const messages = [];
    for (const botName of existing) {
      messages.push(await this.resetNativeTaskBot(botName, { resume }));
    }
    return messages.join('\n\n');
  }

  async nativeToolRestock(name = 'kira') {
    const botName = safeName(name) || 'kira';
    await this.ensureNativeTaskClient(botName);
    await fs.writeFile(nativeCommandFile(botName), 'toolrestock\n', 'utf8');
    const active = this.activeTasks.get(botName) || await this.#loadPersistedNativeTask(botName);
    if (active?.commands?.length) {
      setTimeout(() => {
        void this.sendNativeBaritoneCommands(active.commands, { ensureLaunch: false, botName })
          .catch((error) => console.error(`Failed to resume native task after restock: ${error.message}`));
      }, 18000);
      return `Sent tool restock request. \`${botName}\` will pull repaired tools/armor, equip repaired armor if needed, then resume the last native task.`;
    }
    return `Sent tool restock request. \`${botName}\` will go to the tool chest at \`24 63 -192\` and pull repaired tools/armor.`;
  }

  async #loadPersistedNativeTask(name = 'kira') {
    try {
      const raw = await fs.readFile(nativeTaskStateFile(name), 'utf8');
      const parsed = JSON.parse(raw);
      const task = parsed?.task;
      if (task?.active !== false && (task?.payload || (Array.isArray(task?.commands) && task.commands.length))) {
        this.activeTasks.set(parsed.bot || safeName(name) || 'kira', task);
        return task;
      }
    } catch {}
    return null;
  }

  async #resumePersistedManagedBots() {
    const store = await loadTaskStore();
    for (const botName of Object.keys(store.bots || {}).map(safeName).filter(Boolean)) {
      const task = await this.#loadPersistedNativeTask(botName);
      if (!task?.payload) continue;
      console.log(`[task:${botName}] launching to resume persisted managed task ${task.taskId}.`);
      try {
        await this.ensureNativeTaskClient(botName);
      } catch (error) {
        console.error(`[task:${botName}] managed task recovery failed: ${error.message}`);
      }
    }
  }

  async nativeToolDurability(name = 'kira') {
    const botName = safeName(name) || 'kira';
    const launch = await this.#nativeLaunchConfig(botName);
    await this.ensureNativeTaskClient(botName);
    const logPath = launch.logPath;
    const before = await fs.readFile(logPath, 'utf8').catch(() => '');
    const previous = this.#lastDurabilityLine(before);
    await fs.writeFile(nativeCommandFile(botName), 'durability\n', 'utf8');
    for (let i = 0; i < 40; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 500));
      const current = await fs.readFile(logPath, 'utf8').catch(() => '');
      const line = this.#lastDurabilityLine(current);
      if (line && line !== previous) {
        return line
          .replace(/^.*TaskBotDurability:/, 'TaskBotDurability:')
          .replace('TaskBotDurability:;', 'TaskBotDurability:')
          .slice(0, 1800);
      }
    }
    throw new Error('Timed out waiting for durability report from native client.');
  }

  #lastDurabilityLine(logText) {
    return logText.split(/\r?\n/).filter((line) => line.includes('TaskBotDurability:')).at(-1) || '';
  }

  async #nativeLaunchConfig(name = 'kira') {
    const botName = safeName(name) || 'kira';
    const store = await loadTaskStore();
    const bot = store.bots[botName] || Object.values(store.bots || {})[0] || {};
    const host = clean(bot.host || process.env.TASK_SERVER_HOST || '209.25.141.24');
    const port = Number.parseInt(bot.port || process.env.TASK_SERVER_PORT || 1306, 10);
    const nativeRoot = clean(process.env.TASK_NATIVE_ROOT || '/home/diamond/baritone-task-client');
    const basePrismDir = path.join(nativeRoot, 'prism');
    const prismDir = clean(bot.prismDir || (botName === 'kira' ? basePrismDir : path.join(nativeRoot, `prism-${botName}`)));
    const launchScript = clean(process.env.TASK_NATIVE_LAUNCH || path.join(nativeRoot, 'bin', 'launch-headless.sh'));
    const defaultInstance = clean(process.env.TASK_NATIVE_INSTANCE || 'baritone-26.2-native');
    const instanceId = clean(bot.instanceId || bot.nativeInstance || (botName === 'kira' ? defaultInstance : `${defaultInstance}-${botName}`));
    return {
      botName,
      bot,
      host,
      port,
      server: `${host}:${port}`,
      nativeRoot,
      basePrismDir,
      prismDir,
      launchScript,
      instanceId,
      profile: clean(bot.profile || bot.displayName || ''),
      logPath: path.join(prismDir, 'instances', instanceId, '.minecraft', 'logs', 'latest.log'),
      launcherLogPath: path.join(nativeRoot, 'logs', `prism-headless-${botName}.log`),
      instancePath: path.join(prismDir, 'instances', instanceId)
    };
  }

  async #ensureNativePrismRoot(config) {
    try {
      await fs.access(config.prismDir);
      return;
    } catch {}

    await fs.mkdir(config.prismDir, { recursive: true });
    for (const entry of ['assets', 'icons', 'libraries', 'meta']) {
      const source = path.join(config.basePrismDir, entry);
      const target = path.join(config.prismDir, entry);
      try {
        await fs.symlink(source, target, 'dir');
      } catch (error) {
        if (error.code !== 'EEXIST') throw error;
      }
    }
    for (const file of ['accounts.json', 'prismlauncher.cfg']) {
      const source = path.join(config.basePrismDir, file);
      const target = path.join(config.prismDir, file);
      try {
        await fs.copyFile(source, target);
      } catch (error) {
        if (error.code !== 'ENOENT') throw error;
      }
    }
    await fs.mkdir(path.join(config.prismDir, 'instances'), { recursive: true });
  }

  async #ensureNativeInstance(config) {
    try {
      await fs.access(path.join(config.instancePath, 'instance.cfg'));
      return;
    } catch {}

    const source = path.join(config.basePrismDir, 'instances', clean(process.env.TASK_NATIVE_INSTANCE || 'baritone-26.2-native'));
    await fs.cp(source, config.instancePath, { recursive: true, force: false, errorOnExist: false });
    const cfgPath = path.join(config.instancePath, 'instance.cfg');
    const cfg = await fs.readFile(cfgPath, 'utf8');
    const updated = cfg
      .replace(/^name=.*$/m, `name=Baritone 26.2 ${config.botName}`)
      .replace(/^JoinServerOnLaunchAddress=.*$/m, `JoinServerOnLaunchAddress=${config.server}`);
    await fs.writeFile(cfgPath, updated, 'utf8');
    await fs.rm(path.join(config.instancePath, '.minecraft', 'logs'), { recursive: true, force: true });
  }

  async #resetNativeRuntimeState(config) {
    await fs.rm(nativeCommandFile(config.botName), { force: true });
    await fs.rm(config.logPath, { force: true });
    await fs.mkdir(path.join(config.instancePath, '.minecraft', 'baritone'), { recursive: true });
    await fs.writeFile(path.join(config.instancePath, '.minecraft', 'baritone', 'settings.txt'), NATIVE_SAFE_BARITONE_SETTINGS, 'utf8');
  }

  async #waitForNativeTaskBotReady(config) {
    for (let i = 0; i < 45; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 1000));
      const log = await fs.readFile(config.logPath, 'utf8').catch(() => '');
      if (/\[HiveMiner\] Connected to controller|\[HiveMiner\] Initializing safety-first task controller/.test(log)) {
        return;
      }
    }
    throw new Error(`Native task client \`${config.botName}\` launched but the managed controller was not ready yet.`);
  }

  async #validateNativeProfile(config) {
    if (!config.profile) return;
    const accountsPath = path.join(config.prismDir, 'accounts.json');
    let accounts;
    try {
      accounts = JSON.parse(await fs.readFile(accountsPath, 'utf8'));
    } catch {
      throw new Error(`Prism accounts file is missing at ${accountsPath}. Log the account into Prism first.`);
    }
    const names = (accounts.accounts || [])
      .map((account) => clean(account?.profile?.name || account?.profileName || account?.username))
      .filter(Boolean);
    if (!names.includes(config.profile)) {
      throw new Error(`Prism profile "${config.profile}" is not logged in. Available profile(s): ${names.length ? names.join(', ') : 'none'}.`);
    }
  }

  async ensureNativeTaskClient(name = 'kira', { forceRestart = false } = {}) {
    const config = await this.#nativeLaunchConfig(name);
    await this.#ensureNativePrismRoot(config);
    await this.#ensureNativeInstance(config);
    await this.#validateNativeProfile(config);

    const processScan = nativeProcessScan(config.instanceId);
    const check = await execFileAsync('/usr/bin/bash', ['-lc', processScan], { timeout: 10000 })
      .then(({ stdout }) => stdout.trim().length > 0)
      .catch(() => false);
    if (check && !forceRestart) return `Native Baritone client is already running for \`${config.botName}\`.`;

    if (check && forceRestart) {
      await execFileAsync('/usr/bin/bash', ['-lc', `
set +e
(${processScan}) | while read -r pid rest; do
  kill "$pid" 2>/dev/null
done
sleep 5
rm -f ${shellSingleQuote(nativeCommandFile(config.botName))}
      `], { timeout: 20000 });
    } else {
      await fs.rm(nativeCommandFile(config.botName), { force: true });
    }

    // A previous successful launch must never make a failed relaunch look healthy.
    await fs.rm(config.logPath, { force: true });
    await fs.rm(config.launcherLogPath, { force: true });

    const child = spawn(config.launchScript, [config.server], {
      cwd: config.nativeRoot,
      detached: true,
      stdio: 'ignore',
      env: {
        ...process.env,
        INSTANCE_ID: config.instanceId,
        PRISM_DIR: config.prismDir,
        PROFILE: config.profile,
        TASK_BOT_ID: config.botName,
        TASK_LAUNCH_LOG: config.launcherLogPath,
        TASK_NATIVE_COMMAND_FILE: nativeCommandFile(config.botName)
      }
    });
    child.unref();

    for (let i = 0; i < 60; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 1000));
      const running = await execFileAsync('/usr/bin/bash', ['-lc', processScan], { timeout: 10000 })
        .then(({ stdout }) => stdout.trim().length > 0)
        .catch(() => false);
      try {
        const log = await fs.readFile(config.logPath, 'utf8');
        if (running && /Loaded [0-9]+ advancements|Baritone world data dir/.test(log)) {
          return `Launched native Baritone client for \`${config.botName}\` -> \`${config.server}\`.`;
        }
      } catch {}
      if (i >= 5 && !running) {
        const launcherLog = await fs.readFile(config.launcherLogPath, 'utf8').catch(() => '');
        const detail = launcherLog.trim().split(/\r?\n/).slice(-8).join(' | ').slice(0, 1200);
        throw new Error(`Native task client \`${config.botName}\` exited during launch${detail ? `: ${detail}` : '.'}`);
      }
    }
    throw new Error(`Native task client \`${config.botName}\` is running but did not join \`${config.server}\` within 60 seconds.`);
  }

  async sendNativeBaritoneCommands(commands, { ensureLaunch = true, botName = 'kira' } = {}) {
    const targetBot = safeName(botName) || 'kira';
    const safeCommands = (commands || []).map(clean).filter(Boolean);
    if (!safeCommands.length) throw new Error('No Baritone commands to send.');
    for (const command of safeCommands) {
      if (!/^(#(sel( clear| pos[12] -?\d+ -?\d+ -?\d+| cleararea)?|goto -?\d+ -?\d+ -?\d+|goal -?\d+ -?\d+ -?\d+|mine [a-z0-9:_./-]+|elytra( supported| reset| repack)?|set (elytra(TermsAccepted|AutoJump|ConserveFireworks) (true|false)|elytra(MinimumDurability|MinFireworksBeforeLanding) \d+|buildOnlySelection (true|false)|buildInLayers (true|false)|breakFromAbove (true|false)|layerHeight \d+)|stop|pause|resume)|clearbox -?\d+ -?\d+ -?\d+ -?\d+ -?\d+ -?\d+)$/i.test(command)) {
        throw new Error(`Refusing unsafe Baritone command: ${command}`);
      }
    }
    if (ensureLaunch) await this.ensureNativeTaskClient(targetBot);

    const payload = `${safeCommands.map((command) => command.startsWith('#') ? `baritone:${command}` : command).join('\n')}\n`;
    const commandFile = nativeCommandFile(targetBot);
    await fs.writeFile(commandFile, payload, 'utf8');

    const deadline = Date.now() + Math.max(15000, safeCommands.length * 5000);
    while (Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 250));
      try {
        await fs.access(commandFile);
      } catch {
        return;
      }
    }
    await fs.rm(commandFile, { force: true });
    throw new Error(`Timed out waiting for native task client \`${targetBot}\` to consume Baritone command file. Run \`/taskrejoin name:${targetBot}\` to restart the native client and resume the last task.`);
  }

  #resolveConfiguredTaskBots(selection, store) {
    const configured = Object.keys(store.bots || {}).map(safeName).filter(Boolean);
    const preferred = ['kira', 'azure'].filter((name) => configured.includes(name));
    const all = [...preferred, ...configured.filter((name) => !preferred.includes(name))];
    const raw = clean(selection || 'all').toLowerCase();
    const requested = raw === 'all' ? all : raw.split(',').map(safeName).filter(Boolean);
    const missing = requested.filter((name) => !configured.includes(name));
    if (missing.length) throw new Error(`Unknown task bot(s): ${missing.join(', ')}.`);
    if (!requested.length) throw new Error('No task bots are configured.');
    return [...new Set(requested)];
  }

  #resolveConnectedBots(selection, { allowEmpty = false } = {}) {
    const clients = [...new Set([
      ...this.control.listClients().map((client) => client.botId),
      ...[...this.launches.entries()].filter(([, session]) => session.backend === 'botcraft').map(([name]) => name)
    ])];
    const raw = clean(selection || 'all').toLowerCase();
    const requested = raw === 'all' ? clients : raw.split(',').map(safeName).filter(Boolean);
    const connected = requested.filter((name) => clients.includes(name));
    if (!connected.length && !allowEmpty) throw new Error('No selected task bots are launched. Use `/tasklaunch` first.');
    return connected;
  }

  #resolveLaunchedBotName(name) {
    const requested = safeName(name);
    if (requested) return requested;
    const launched = [...this.launches.keys()];
    if (launched.length === 1) return launched[0];
    if (!launched.length) throw new Error('No task bots are launched. Use `/tasklaunch` first.');
    throw new Error('More than one task bot is launched. Pass the bot name.');
  }

  async #appendBotcraftCommand(name, command) {
    const botName = safeName(name);
    const session = this.launches.get(botName);
    if (!session?.commandFile) throw new Error(`Task bot "${botName}" is not launched with Botcraft.`);
    await fs.appendFile(session.commandFile, `${command}\n`);
  }

  async #ensureBotcraftAssets(botcraftBin, sessionDir) {
    const target = path.join(sessionDir, 'Assets');
    try {
      await fs.access(path.join(target, '26.2', 'minecraft'));
      return;
    } catch {}

    const source = path.join(path.dirname(botcraftBin), 'Assets');
    await fs.access(path.join(source, '26.2', 'minecraft'));
    try {
      await fs.symlink(source, target, 'dir');
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
    }
  }

  #waitForControlConnection(botName, timeoutMs) {
    if (this.control.getClient(botName)) return Promise.resolve(true);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.control.off('connect', onConnect);
        reject(new Error(`Timed out waiting for "${botName}" to connect to controller.`));
      }, timeoutMs);
      timer.unref?.();

      const onConnect = (event) => {
        if (event.botId !== botName) return;
        clearTimeout(timer);
        this.control.off('connect', onConnect);
        resolve(true);
      };

      this.control.on('connect', onConnect);
    });
  }

  #handleLauncherLine(botName, session, line, events) {
    const minecraftName = this.#extractMinecraftName(line);
    if (minecraftName) {
      session.username = minecraftName;
      void this.#saveTaskDisplayName(botName, minecraftName);
    }

    if (/microsoft|verification|enter code|user code|device code|https?:\/\/|aka\.ms|microsoft\.com\/link/i.test(line)) {
      if (!session.notifiedLogin || /code/i.test(line)) {
        session.notifiedLogin = true;
        if (events.onLoginHint) events.onLoginHint(line);
      }
    }

    if (/Connection to server established|Successfully connected|Spawned|Joined|joined/i.test(line) && events.onEvent) {
      events.onEvent(`\`${session.username || botName}\` task bot joined \`${session.server}\`.`);
    }
  }

  #extractMinecraftName(text) {
    const patterns = [
      /Authenticated "[^"]+" as ([A-Za-z0-9_]{3,16})\./,
      /NetworkPacketProcessing - ([A-Za-z0-9_]{3,16})\(/,
      /Logged in as ([A-Za-z0-9_]{3,16})/,
      /username[=: ]+([A-Za-z0-9_]{3,16})/i
    ];
    for (const pattern of patterns) {
      const match = text.match(pattern);
      if (match) return match[1];
    }
    return null;
  }

  async #saveTaskDisplayName(name, displayName) {
    const store = await loadTaskStore();
    if (!store.bots[name] || store.bots[name].displayName === displayName) return;
    store.bots[name].displayName = displayName;
    store.bots[name].updatedAt = new Date().toISOString();
    await saveTaskStore(store);
  }

  #handleBotConnect({ botId, username }) {
    const reconnectTimer = this.taskReconnectTimers.get(botId);
    if (reconnectTimer) clearTimeout(reconnectTimer);
    this.taskReconnectTimers.delete(botId);
    this.taskReconnectAttempts.delete(botId);
    console.log(`[task:${botId}] ${username} connected to controller.`);
    void (async () => {
      if (!this.activeTasks.has(botId)) await this.#loadPersistedNativeTask(botId);
      if (!this.activeTasks.get(botId)?.payload) return;
      this.pendingResumeChecks.add(botId);
      this.control.send(botId, { type: 'ping' });
    })().catch((error) => console.error(`[task:${botId}] failed resume check: ${error.message}`));
  }

  #handleBotDisconnect({ botId, username }) {
    console.log(`[task:${botId}] ${username} disconnected from controller.`);
    if (this.activeTasks.get(botId)?.payload) this.#scheduleManagedReconnect(botId);
  }

  #scheduleManagedReconnect(botId) {
    if (this.taskReconnectTimers.has(botId)) return;
    const attempt = (this.taskReconnectAttempts.get(botId) || 0) + 1;
    this.taskReconnectAttempts.set(botId, attempt);
    const delayMs = Math.min(60000, 15000 * (2 ** Math.min(attempt - 1, 2)));
    console.log(`[task:${botId}] managed client reconnect scheduled in ${delayMs / 1000}s (attempt ${attempt}).`);
    const timer = setTimeout(() => {
      this.taskReconnectTimers.delete(botId);
      void (async () => {
        if (this.control.getClient(botId) || !this.activeTasks.get(botId)?.payload) return;
        await this.ensureNativeTaskClient(botId, { forceRestart: true });
        await this.#waitForControlConnection(botId, 60000);
      })().catch((error) => {
        console.error(`[task:${botId}] managed client reconnect failed: ${error.message}`);
        this.#scheduleManagedReconnect(botId);
      });
    }, delayMs);
    timer.unref?.();
    this.taskReconnectTimers.set(botId, timer);
  }

  #handleBotMessage({ botId, username, message }) {
    if (message.type === 'event') {
      console.log(`[task:${botId}] ${username}: ${message.message || 'event'}`);
    }
    if (message.type === 'status') {
      const position = message.position ? `${message.position.x},${message.position.y},${message.position.z}` : 'offline';
      const signature = [message.taskId, message.stage, message.completedCells, message.cellRemaining, message.blocker, position].join('|');
      if (this.botDiagnostics.get(botId) !== signature) {
        this.botDiagnostics.set(botId, signature);
        console.log(`[task:${botId}] stage=${message.stage || 'unknown'} pos=${position} cells=${message.completedCells || 0}/${message.totalCells || 0} remaining=${message.cellRemaining || 0}${message.blocker ? ` blocker=${message.blocker}` : ''}`);
      }
    }
    if (message.type === 'status' && this.pendingResumeChecks.delete(botId)) {
      const active = this.activeTasks.get(botId);
      if (active?.payload && message.taskId !== active.taskId) {
        console.log(`[task:${botId}] replaying persisted managed task ${active.taskId}.`);
        this.control.send(botId, { type: 'task', task: active.payload });
      }
    }
    if (message.type === 'complete' || message.type === 'stopped' || message.type === 'error') {
      const task = this.activeTasks.get(botId);
      if (message.type !== 'error') {
        this.activeTasks.delete(botId);
        if (task) {
          const finished = {
            ...task,
            active: false,
            finishedAt: new Date().toISOString(),
            result: message.type,
            resultMessage: message.message || ''
          };
          void fs.writeFile(nativeTaskStateFile(botId), JSON.stringify({ bot: botId, task: finished }, null, 2), 'utf8')
            .catch((error) => console.error(`[task:${botId}] failed to mark task inactive: ${error.message}`));
        }
      }
      console.log(`[task:${botId}] ${username}: ${message.type} ${message.message || message.error || ''}`);
      if (message.type === 'complete' && task) {
        void this.#notifyTaskComplete(task, botId, username);
      }
    }
  }

  async #notifyTaskComplete(task, botId, username) {
    if (!this.discordClient) return;
    const content = `\`${username || botId}\` finished \`${task.kind}\` task \`${task.taskId}\`.`;
    try {
      if (task.notifyChannelId) {
        const channel = await this.discordClient.channels.fetch(task.notifyChannelId);
        if (channel?.isTextBased()) {
          await channel.send(task.notifyUserId ? `<@${task.notifyUserId}> ${content}` : content);
          return;
        }
      }
      if (task.notifyUserId) {
        const user = await this.discordClient.users.fetch(task.notifyUserId);
        if (user) {
          await user.send(content);
          return;
        }
      }
      if (task.notifyChannelId) {
        const channel = await this.discordClient.channels.fetch(task.notifyChannelId);
        if (channel?.isTextBased()) {
          await channel.send(content);
        }
      }
    } catch (error) {
      console.error(`Failed to send task completion notification: ${error.message}`);
    }
  }
}

module.exports = { TaskManager };
