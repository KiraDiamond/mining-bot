const fs = require('fs/promises');
const path = require('path');
const readline = require('readline/promises');
const { spawn } = require('child_process');
const { stdin: input, stdout: output } = require('process');
const mc = require('minecraft-protocol');

const rootDir = path.resolve(__dirname, '..');
const dataDir = path.join(rootDir, 'data');
const farmsPath = path.join(dataDir, 'farms.json');
const oneShotMode = process.argv.length > 2;
const defaultBotcraftBin = path.join(process.env.HOME || process.env.USERPROFILE || '', 'botcraft-26.2', 'botcraft', 'bin', '3_SimpleAFKExample');

const rl = readline.createInterface({ input, output, prompt: 'afk> ' });
const running = new Map();

function log(message) {
  console.log(`[${new Date().toLocaleTimeString()}] ${message}`);
}

function safeName(value) {
  return String(value).replace(/[^a-zA-Z0-9._-]+/g, '_').slice(0, 80);
}

async function ensureDataDir() {
  await fs.mkdir(dataDir, { recursive: true });
}

async function loadStore() {
  await ensureDataDir();
  try {
    const raw = await fs.readFile(farmsPath, 'utf8');
    return JSON.parse(raw);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    return { farms: {} };
  }
}

async function saveStore(store) {
  await ensureDataDir();
  await fs.writeFile(farmsPath, JSON.stringify(store, null, 2));
}

function parseArgs(text) {
  const args = [];
  const re = /"([^"]*)"|'([^']*)'|(\S+)/g;
  let match;
  while ((match = re.exec(text)) !== null) args.push(match[1] ?? match[2] ?? match[3]);
  return args;
}

function parseOptions(args) {
  const options = { _: [] };
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (!arg.startsWith('--')) {
      options._.push(arg);
      continue;
    }
    const key = arg.slice(2);
    const next = args[i + 1];
    if (!next || next.startsWith('--')) {
      options[key] = true;
    } else {
      options[key] = next;
      i += 1;
    }
  }
  return options;
}

async function askDefault(question, fallback) {
  const answer = (await rl.question(`${question}${fallback ? ` [${fallback}]` : ''}: `)).trim();
  return answer || fallback;
}

async function setFarm(args) {
  const opts = parseOptions(args);
  const name = opts._[0];
  if (!name) {
    log('Usage: /afkset <farm> --account <email> --host <server> [--port 25565] [--version auto]');
    return;
  }

  const store = await loadStore();
  const existing = store.farms[name] || {};
  const account = opts.account || opts.email || await askDefault('Microsoft account email / cache id', existing.account);
  const host = opts.host || await askDefault('Server host', existing.host);
  const portText = opts.port || await askDefault('Server port', String(existing.port || 25565));
  const version = opts.version || await askDefault('Protocol version, use auto unless needed', existing.version || 'auto');
  const auth = opts.auth || await askDefault('Auth mode: microsoft or offline', existing.auth || 'microsoft');

  if (!account || !host) {
    log('Farm was not saved. Account and host are required.');
    return;
  }

  store.farms[name] = {
    name,
    account,
    host,
    port: Number.parseInt(portText, 10) || 25565,
    version,
    auth,
    reconnect: opts.reconnect !== 'false',
    createdAt: existing.createdAt || new Date().toISOString(),
    updatedAt: new Date().toISOString()
  };
  await saveStore(store);
  log(`Saved farm "${name}" for ${account} on ${host}:${store.farms[name].port}.`);
}

function createClientOptions(farm) {
  const profilesFolder = path.join(dataDir, 'auth', safeName(farm.account));
  return {
    host: farm.host,
    port: farm.port || 25565,
    username: farm.account,
    auth: farm.auth || 'microsoft',
    version: !farm.version || farm.version === 'auto' ? false : farm.version,
    profilesFolder,
    keepAlive: true,
    hideErrors: true,
    disableChatSigning: true,
    onMsaCode: (data) => {
      log(`Microsoft login needed for ${farm.account}`);
      if (data.message) log(data.message);
      if (data.verification_uri && data.user_code) {
        log(`Open ${data.verification_uri} and enter code ${data.user_code}`);
      }
    }
  };
}

function shouldUseBotcraft(farm) {
  const backend = String(farm.backend || process.env.AFK_BACKEND || '').toLowerCase();
  const version = String(farm.version || '').toLowerCase();
  return backend === 'botcraft' || version === '26.2';
}

async function startBotcraftFarm(name, farm) {
  const botcraftBin = process.env.BOTCRAFT_AFK_BIN || defaultBotcraftBin;
  const address = `${farm.host}:${farm.port || 25565}`;
  const farmDir = path.join(dataDir, 'botcraft', safeName(name));
  await fs.mkdir(farmDir, { recursive: true });

  const args = ['--address', address];
  if ((farm.auth || 'microsoft').toLowerCase() === 'offline') {
    args.push('--login', farm.account || name);
  } else {
    args.push('--login', '');
  }

  let child = null;
  let reconnectTimer = null;
  let stopped = false;
  const crashTimes = [];

  const session = {
    stop() {
      stopped = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (child) child.kill('SIGTERM');
    },
    get child() {
      return child;
    }
  };
  running.set(name, session);

  const relay = (chunk, isError = false) => {
    const text = chunk.toString().trim();
    if (!text) return;
    for (const line of text.split(/\r?\n/)) {
      log(isError ? `[botcraft stderr] ${line}` : line);
      if (!stopped && /Disconnect during playing with reason/i.test(line)) {
        log(`"${name}" Botcraft reported a server disconnect; restarting connection.`);
        if (child) child.kill('SIGTERM');
      }
    }
  };

  const scheduleReconnect = (code, signal) => {
    if (stopped || farm.reconnect === false) {
      running.delete(name);
      if (oneShotMode) process.exit(0);
      return;
    }

    const now = Date.now();
    const crashed = signal || (typeof code === 'number' && code !== 0);
    if (crashed) crashTimes.push(now);
    while (crashTimes.length && now - crashTimes[0] > 10 * 60 * 1000) crashTimes.shift();

    const delaySeconds = crashed
      ? Math.min(300, 15 * (2 ** Math.min(crashTimes.length - 1, 5)))
      : 15;
    reconnectTimer = setTimeout(connect, delaySeconds * 1000);
    log(`"${name}" Botcraft will restart in ${delaySeconds}s${crashTimes.length ? ` after ${crashTimes.length} crash(es) in 10m` : ''}.`);
  };

  const connect = () => {
    if (stopped) return;
    log(`Starting "${name}" with Botcraft -> ${address}`);
    child = spawn(botcraftBin, args, {
      cwd: farmDir,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: {
        ...process.env,
        LD_LIBRARY_PATH: [
          path.dirname(botcraftBin),
          process.env.LD_LIBRARY_PATH
        ].filter(Boolean).join(':')
      }
    });

    child.stdout.on('data', (chunk) => relay(chunk));
    child.stderr.on('data', (chunk) => relay(chunk, true));
    child.on('error', (error) => {
      log(`"${name}" Botcraft failed to start: ${error.message}`);
      scheduleReconnect(1, null);
    });
    child.on('exit', (code, signal) => {
      child = null;
      log(`"${name}" Botcraft exited code=${code ?? ''} signal=${signal || ''}.`);
      scheduleReconnect(code, signal);
    });
  };

  connect();
}

async function startFarm(name, farm) {
  if (running.has(name)) {
    log(`Farm "${name}" is already running.`);
    return;
  }

  if (shouldUseBotcraft(farm)) {
    await startBotcraftFarm(name, farm);
    return;
  }

  let stopped = false;
  let reconnectTimer = null;
  let client = null;

  const session = {
    stop() {
      stopped = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (client) client.end('Stopped by AFK manager');
    }
  };
  running.set(name, session);

  const connect = () => {
    if (stopped) return;
    log(`Starting "${name}" as ${farm.account} -> ${farm.host}:${farm.port || 25565}`);
    client = mc.createClient(createClientOptions(farm));
    session.client = client;

    client.once('session', (sessionData) => {
      const profile = sessionData && sessionData.selectedProfile;
      if (profile && profile.name) log(`Authenticated "${name}" as ${profile.name}.`);
    });

    client.once('login', () => log(`"${name}" logged in.`));
    client.once('playerJoin', () => log(`"${name}" joined the world and is AFK.`));

    client.on('kick_disconnect', (packet) => {
      log(`"${name}" kicked: ${JSON.stringify(packet.reason || packet)}`);
    });

    client.on('error', (error) => {
      log(`"${name}" error: ${error.message}`);
      if (/No data available for version/i.test(error.message)) {
        stopped = true;
        running.delete(name);
        if (oneShotMode) setTimeout(() => process.exit(2), 100);
      }
    });

    client.on('end', (reason) => {
      log(`"${name}" disconnected${reason ? `: ${reason}` : ''}.`);
      if (!stopped && farm.reconnect !== false) {
        reconnectTimer = setTimeout(connect, 30000);
        log(`"${name}" will reconnect in 30s.`);
      } else {
        running.delete(name);
      }
    });
  };

  connect();
}

async function afk(args) {
  const name = args[0];
  if (!name) {
    log('Usage: /afk <farm>');
    return;
  }
  const store = await loadStore();
  const farm = store.farms[name];
  if (!farm) {
    log(`No farm named "${name}". Use /afkset ${name} first.`);
    return;
  }
  await startFarm(name, farm);
}

async function afkAll() {
  const store = await loadStore();
  for (const [name, farm] of Object.entries(store.farms)) await startFarm(name, farm);
}

async function listFarms() {
  const store = await loadStore();
  const names = Object.keys(store.farms);
  if (!names.length) {
    log('No farms saved.');
    return;
  }
  for (const name of names) {
    const farm = store.farms[name];
    const state = running.has(name) ? 'running' : 'stopped';
    log(`${name}: ${state}, ${farm.account} -> ${farm.host}:${farm.port || 25565}, version=${farm.version || 'auto'}`);
  }
}

function stopFarm(args) {
  const name = args[0];
  if (!name) {
    log('Usage: /stop <farm>');
    return;
  }
  const session = running.get(name);
  if (!session) {
    log(`Farm "${name}" is not running.`);
    return;
  }
  session.stop();
  running.delete(name);
  log(`Stopped "${name}".`);
}

function stopAll() {
  for (const [name, session] of running) {
    session.stop();
    running.delete(name);
    log(`Stopped "${name}".`);
  }
}

async function removeFarm(args) {
  const name = args[0];
  if (!name) {
    log('Usage: /remove <farm>');
    return;
  }
  const store = await loadStore();
  if (!store.farms[name]) {
    log(`No farm named "${name}".`);
    return;
  }
  delete store.farms[name];
  await saveStore(store);
  log(`Removed farm "${name}". Auth cache was left alone.`);
}

function help() {
  console.log(`
Commands:
  /afkset <farm> [--account email] [--host server] [--port 25565] [--version auto] [--auth microsoft]
  /afk <farm>
  /afkall
  /farms
  /stop <farm>
  /stopall
  /remove <farm>
  /quit

Example:
  /afkset iron --account myalt@example.com --host play.example.net --port 25565 --version auto
  /afk iron

First Microsoft login uses a browser device-code prompt. Tokens are cached under data/auth/.
`);
}

async function handleLine(line) {
  const trimmed = line.trim();
  if (!trimmed) return;
  const inputLine = trimmed.startsWith('/') ? trimmed.slice(1) : trimmed;
  const [command, ...args] = parseArgs(inputLine);

  switch ((command || '').toLowerCase()) {
    case 'afkset':
      await setFarm(args);
      break;
    case 'afk':
      await afk(args);
      break;
    case 'afkall':
      await afkAll();
      break;
    case 'farms':
    case 'list':
      await listFarms();
      break;
    case 'stop':
      stopFarm(args);
      break;
    case 'stopall':
      stopAll();
      break;
    case 'remove':
      await removeFarm(args);
      break;
    case 'help':
      help();
      break;
    case 'quit':
    case 'exit':
      stopAll();
      rl.close();
      process.exit(0);
      break;
    default:
      log(`Unknown command "${command}". Type /help.`);
  }
}

process.on('SIGINT', () => {
  stopAll();
  rl.close();
  process.exit(0);
});

process.on('SIGTERM', () => {
  stopAll();
  rl.close();
  process.exit(0);
});

async function main() {
  await ensureDataDir();

  const oneShot = process.argv.slice(2).join(' ').trim();
  if (oneShot) {
    await handleLine(oneShot);
    rl.close();
    return;
  }

  help();
  rl.prompt();
  rl.on('line', async (line) => {
    try {
      await handleLine(line);
    } catch (error) {
      log(`Command failed: ${error.stack || error.message}`);
    }
    rl.prompt();
  });
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
