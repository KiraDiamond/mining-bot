require('dotenv').config();

const fs = require('fs/promises');
const path = require('path');
const { spawn } = require('child_process');
const { Client, Events } = require('discord.js');
const { TaskManager } = require('./task-manager');
const { planTask } = require('./taskbot-planner');
const { GroqService } = require('./groq-service');
const { ProjectEditor } = require('./project-editor');
let Authflow;
let Titles;
try {
  ({ Authflow, Titles } = require('prismarine-auth'));
} catch {}

const rootDir = path.resolve(__dirname, '..');
const dataDir = path.join(rootDir, 'data');
const farmsPath = path.join(dataDir, 'farms.json');
const ownersPath = path.join(dataDir, 'owners.json');
const running = new Map();
const groq = new GroqService();
const projectEditor = new ProjectEditor({ rootDir });

function clean(value) {
  return String(value || '').trim();
}

function safeFarmName(value) {
  return clean(value).toLowerCase().replace(/[^a-z0-9._-]+/g, '-').slice(0, 48);
}

function publicAccountName(farm) {
  if (clean(farm.displayName)) return clean(farm.displayName);
  return 'Account';
}

function extractMinecraftName(text) {
  const patterns = [
    /Authenticated "[^"]+" as ([A-Za-z0-9_]{3,16})\./,
    /NetworkPacketProcessing - ([A-Za-z0-9_]{3,16})\(/
  ];
  for (const pattern of patterns) {
    const match = text.match(pattern);
    if (match) return match[1];
  }
  return null;
}

function parseDuration(value) {
  const input = clean(value).toLowerCase();
  if (!input) return null;

  const unitMs = {
    d: 24 * 60 * 60 * 1000,
    h: 60 * 60 * 1000,
    m: 60 * 1000,
    s: 1000
  };
  const matches = [...input.matchAll(/(\d+(?:\.\d+)?)([dhms])/g)];
  if (!matches.length || matches.map((match) => match[0]).join('') !== input) {
    throw new Error('Invalid time. Use values like `30m`, `4h`, `1h30m`, or `2d`.');
  }

  const total = matches.reduce((sum, match) => sum + Number.parseFloat(match[1]) * unitMs[match[2]], 0);
  if (!Number.isFinite(total) || total < 1000) throw new Error('Time must be at least 1 second.');
  if (total > 14 * 24 * 60 * 60 * 1000) throw new Error('Time cannot be longer than 14 days.');
  return Math.round(total);
}

function formatDuration(ms) {
  if (!ms) return '';
  const parts = [];
  const units = [
    ['d', 24 * 60 * 60 * 1000],
    ['h', 60 * 60 * 1000],
    ['m', 60 * 1000],
    ['s', 1000]
  ];
  let remaining = ms;
  for (const [label, unitMs] of units) {
    const value = Math.floor(remaining / unitMs);
    if (value) {
      parts.push(`${value}${label}`);
      remaining -= value * unitMs;
    }
  }
  return parts.join('');
}

async function ensureDataDir() {
  await fs.mkdir(dataDir, { recursive: true });
}

async function readJson(file, fallback) {
  await ensureDataDir();
  try {
    return JSON.parse(await fs.readFile(file, 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    return fallback;
  }
}

async function writeJson(file, value) {
  await ensureDataDir();
  await fs.writeFile(file, JSON.stringify(value, null, 2));
}

async function loadStore() {
  return readJson(farmsPath, { farms: {} });
}

async function saveStore(store) {
  await writeJson(farmsPath, store);
}

async function saveDisplayName(name, displayName) {
  const store = await loadStore();
  if (!store.farms[name]) return;
  if (store.farms[name].displayName === displayName) return;
  store.farms[name].displayName = displayName;
  store.farms[name].updatedAt = new Date().toISOString();
  await saveStore(store);
}

async function resolveMinecraftName(name, farm) {
  if (clean(farm.displayName)) return farm.displayName;
  if (!Authflow || !Titles || clean(farm.auth || 'microsoft').toLowerCase() !== 'microsoft') return null;

  const profilesFolder = path.join(dataDir, 'auth', clean(farm.account).replace(/[^a-zA-Z0-9._-]+/g, '_').slice(0, 80));
  try {
    const cacheFiles = await fs.readdir(profilesFolder);
    if (!cacheFiles.some((file) => file.endsWith('_mca-cache.json'))) return null;

    const flow = new Authflow(
      farm.account,
      profilesFolder,
      { flow: 'msal', authTitle: Titles.MinecraftJava },
      () => {
        throw new Error('Microsoft auth cache is missing or expired.');
      }
    );
    const token = await Promise.race([
      flow.getMinecraftJavaToken({ fetchProfile: true }),
      new Promise((_, reject) => setTimeout(() => reject(new Error('Timed out resolving Minecraft profile.')), 8000))
    ]);
    const displayName = clean(token?.profile?.name || token?.selectedProfile?.name || token?.name || token?.username);
    if (!displayName) return null;
    farm.displayName = displayName;
    await saveDisplayName(name, displayName);
    return displayName;
  } catch (error) {
    console.warn(`[${name}] Could not resolve Minecraft profile name: ${error.message}`);
    return null;
  }
}

async function loadOwners() {
  const envOwners = clean(process.env.OWNER_IDS).split(',').map((id) => id.trim()).filter(Boolean);
  const stored = await readJson(ownersPath, { owners: [] });
  return [...new Set([...envOwners, ...(stored.owners || [])])];
}

async function isOwner(userId) {
  return (await loadOwners()).includes(userId);
}

async function requireOwner(interaction) {
  if (await isOwner(interaction.user.id)) return true;
  await interaction.reply({
    content: 'You are not allowed to control this AFK manager. Run `/claim-owner` if no owner has been claimed yet.',
    ephemeral: true
  });
  return false;
}

function formatFarm(name, farm) {
  const state = running.has(name) ? 'running' : 'stopped';
  return `\`${name}\` ${state} | ${farm.account} -> ${farm.host}:${farm.port || 25565} | version=${farm.version || 'auto'} | auth=${farm.auth || 'microsoft'} | reconnect=${farm.reconnect !== false}`;
}

async function sendLoginHint(interaction, name, text) {
  const clipped = text.length > 1800 ? `${text.slice(0, 1800)}...` : text;
  try {
    await interaction.followUp({
      content: `\`${name}\` login:\n\`\`\`text\n${clipped}\n\`\`\``,
      ephemeral: true
    });
  } catch {}
}

function assistantHeader(title, details = []) {
  const lines = [`${title}`];
  for (const detail of details.filter(Boolean)) lines.push(`- ${detail}`);
  return lines.join('\n');
}

function formatAssistantPlan({ command, path, files, intent, apply }) {
  return [
    `Command: /${command}`,
    `Target: ${path || 'project-wide'}`,
    `Apply: ${apply ? 'yes' : 'no'}`,
    `Affected files: ${files.length ? files.map((file) => `\`${file}\``).join(', ') : 'none identified yet'}`,
    `Intended changes: ${intent}`
  ].join('\n');
}

function readPreview(text, limit = 1800) {
  return text.length > limit ? `${text.slice(0, limit)}...` : text;
}

function clipDiscord(text, limit = 1800) {
  return text.length > limit ? `${text.slice(0, limit)}...` : text;
}

async function analyzeProjectForAssistant(targetPath = '') {
  const files = await projectEditor.listProjectFiles({ maxFiles: 250 });
  const byFolder = new Map();
  for (const file of files) {
    const top = file.split(path.sep)[0];
    byFolder.set(top, (byFolder.get(top) || 0) + 1);
  }
  const issues = [];
  if (files.some((file) => file.includes('.env'))) issues.push('Environment files are present; keep secrets out of commits.');
  if (!files.some((file) => file.endsWith('README.md'))) issues.push('No README found at project root.');
  if (!files.some((file) => file.endsWith('discord-common.js'))) issues.push('Discord command definitions are centralized in discord-common.js.');
  const summary = {
    targetPath,
    fileCount: files.length,
    folders: [...byFolder.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8).map(([name, count]) => `${name}: ${count}`),
    issues
  };
  return summary;
}

async function assistantPromptAnalysis({ command, targetPath, instruction, content, extra }) {
  const files = targetPath ? [targetPath] : [];
  const system = [
    'You are a concise senior code reviewer and refactoring planner for a Discord bot project.',
    'You must produce clear plans with affected files and intended changes.',
    'Return plain text only.'
  ].join('\n');
  const user = [
    `Command: ${command}`,
    targetPath ? `Target path: ${targetPath}` : '',
    instruction ? `Instruction: ${instruction}` : '',
    extra ? `Extra context: ${extra}` : '',
    content ? `Content preview:\n${content.slice(0, 6000)}` : ''
  ].filter(Boolean).join('\n\n');
  if (!groq.isConfigured()) {
    return {
      files,
      intent: instruction || 'Inspect and update the requested file(s).',
      analysis: 'Groq is not configured; using deterministic planning.'
    };
  }
  const { content: result } = await groq.chat({ system, user, responseFormat: null });
  return { files, intent: instruction || 'Inspect and update the requested file(s).', analysis: result };
}

async function buildAssistantResult({ command, targetPath, apply, intent, files, modify }) {
  const plan = formatAssistantPlan({ command, path: targetPath, files, intent, apply });
  if (!apply) return plan;
  const output = await modify();
  return `${plan}\n\nApplied:\n${output}`;
}

async function sendPublic(interaction, content) {
  try {
    if (interaction.channel) await interaction.channel.send(content);
  } catch (error) {
    console.error(`Failed to send public message: ${error.message}`);
  }
}

async function sendPrivateEvent(session, content) {
  try {
    const user = await session.client.users.fetch(session.notifyUserId);
    await user.send(content);
    return;
  } catch (error) {
    console.warn(`Failed to send DM event for ${session.notifyUserId}: ${error.message}`);
  }

  try {
    const channel = await session.client.channels.fetch(session.notifyChannelId);
    if (channel?.isTextBased()) await channel.send(`<@${session.notifyUserId}> ${content}`);
  } catch (error) {
    console.error(`Failed to send fallback event message: ${error.message}`);
  }
}

function stopRunningFarm(name, reason = 'manual') {
  const session = running.get(name);
  if (!session) return false;
  session.expectedStopReason = reason;
  if (session.stopTimer) clearTimeout(session.stopTimer);
  session.child.kill('SIGTERM');
  running.delete(name);
  return true;
}

function spawnFarm(name, farm, interaction, durationMs = null) {
  if (running.has(name)) return { ok: false, message: `\`${name}\` is already running.` };

  const child = spawn(process.execPath, [path.join(__dirname, 'index.js'), 'afk', name], {
    cwd: rootDir,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: process.env
  });

  const session = {
    child,
    startedAt: new Date(),
    farm,
    durationMs,
    stopTimer: null,
    joinAnnounced: Boolean(clean(farm.displayName)),
    notifyUserId: interaction.user.id,
    notifyChannelId: interaction.channelId,
    client: interaction.client,
    lastCrashLine: '',
    lastEventAt: new Map()
  };
  running.set(name, session);

  if (durationMs) {
    session.stopTimer = setTimeout(() => {
      if (!running.has(name)) return;
      stopRunningFarm(name, 'timer');
      void sendPublic(interaction, `${publicAccountName(farm)} is no longer AFK at \`${name}\` after ${formatDuration(durationMs)}.`);
      void sendPrivateEvent(session, `${publicAccountName(farm)} left \`${name}\` because the ${formatDuration(durationMs)} timer ended.`);
    }, durationMs);
    session.stopTimer.unref();
  }

  const notifyOnce = (key, content, cooldownMs = 0) => {
    const now = Date.now();
    const last = session.lastEventAt.get(key) || 0;
    if (cooldownMs && now - last < cooldownMs) return;
    session.lastEventAt.set(key, now);
    void sendPrivateEvent(session, content);
  };

  const processLogLine = (line) => {
    const minecraftName = extractMinecraftName(line);
    if (minecraftName) {
      session.farm.displayName = minecraftName;
      void saveDisplayName(name, minecraftName);
      if (!session.joinAnnounced) {
        session.joinAnnounced = true;
        void sendPublic(interaction, `${minecraftName} is now AFK at \`${name}\`${durationMs ? ` for ${formatDuration(durationMs)}` : ''}.`);
      }
    }

    if (/Connection to server established\./i.test(line)) {
      notifyOnce('connected', `${publicAccountName(session.farm)} joined \`${name}\`.`, 5000);
    }

    if (/Disconnect during playing with reason/i.test(line)) {
      notifyOnce('disconnect', `${publicAccountName(session.farm)} left \`${name}\`: ${line.slice(0, 500)}`);
    }

    if (/Parsing exception|Unable to create data component|terminate called after throwing/i.test(line)) {
      session.lastCrashLine = line;
    }

    const exitMatch = line.match(/"[^"]+" Botcraft exited code=([^ ]*) signal=([^ .]*)/);
    if (exitMatch) {
      const signal = exitMatch[2] || 'unknown';
      const reason = session.lastCrashLine ? ` Reason: ${session.lastCrashLine.slice(0, 500)}` : '';
      notifyOnce('crash', `${publicAccountName(session.farm)} crashed on \`${name}\` (${signal}).${reason}`);
    }

    const restartMatch = line.match(/"[^"]+" Botcraft will restart in ([0-9]+s)(.*)\./);
    if (restartMatch) {
      notifyOnce('restart', `${publicAccountName(session.farm)} will rejoin \`${name}\` in ${restartMatch[1]}${restartMatch[2] || ''}.`);
    }

    if (/Microsoft login needed|verification|enter code|microsoft\.com|https?:\/\//i.test(line)) {
      void sendLoginHint(interaction, name, line);
    }
  };

  const relay = (chunk, isError = false) => {
    const text = chunk.toString().trim();
    if (!text) return;
    console[isError ? 'error' : 'log'](`[${name}] ${text}`);
    for (const line of text.split(/\r?\n/).map((value) => value.trim()).filter(Boolean)) processLogLine(line);
  };

  child.stdout.on('data', (chunk) => relay(chunk));
  child.stderr.on('data', (chunk) => relay(chunk, true));
  child.once('exit', (code, signal) => {
    if (session.stopTimer) clearTimeout(session.stopTimer);
    running.delete(name);
    console.log(`[${name}] exited code=${code} signal=${signal || ''}`);
    if ((signal || code) && !session.expectedStopReason) {
      void sendPrivateEvent(session, `${publicAccountName(session.farm)} controller for \`${name}\` exited code=${code ?? ''} signal=${signal || ''}.`);
    }
  });

  if (clean(farm.displayName)) {
    return { ok: true, message: `${publicAccountName(farm)} is now AFK at \`${name}\`${durationMs ? ` for ${formatDuration(durationMs)}` : ''}.` };
  }
  return { ok: true, message: `Starting \`${name}\`; Minecraft username will be posted when login completes${durationMs ? `, timer ${formatDuration(durationMs)}` : ''}.` };
}

async function handleClaimOwner(interaction) {
  const envOwners = clean(process.env.OWNER_IDS).split(',').map((id) => id.trim()).filter(Boolean);
  const stored = await readJson(ownersPath, { owners: [] });
  const owners = [...new Set([...envOwners, ...(stored.owners || [])])];
  if (owners.length && !owners.includes(interaction.user.id)) {
    await interaction.reply({ content: 'Owner is already configured.', ephemeral: true });
    return;
  }
  if (!stored.owners.includes(interaction.user.id)) {
    stored.owners.push(interaction.user.id);
    await writeJson(ownersPath, stored);
  }
  await interaction.reply({ content: `Owner claimed for <@${interaction.user.id}>.`, ephemeral: true });
}

async function handleAfkSet(interaction) {
  if (!(await requireOwner(interaction))) return;

  const farmName = safeFarmName(interaction.options.getString('farm', true));
  const account = clean(interaction.options.getString('account', true));
  const host = clean(interaction.options.getString('host', true));
  const port = interaction.options.getInteger('port') || 25565;
  const version = clean(interaction.options.getString('version')) || 'auto';
  const auth = clean(interaction.options.getString('auth')) || 'microsoft';

  const store = await loadStore();
  const existing = store.farms[farmName] || {};
  const reconnect = typeof store.config?.rejoin === 'boolean' ? store.config.rejoin : true;
  store.farms[farmName] = {
    name: farmName,
    account,
    host,
    port,
    version,
    auth,
    reconnect,
    createdAt: existing.createdAt || new Date().toISOString(),
    updatedAt: new Date().toISOString()
  };
  await saveStore(store);
  await interaction.reply({ content: `Saved \`${farmName}\` for \`${account}\` on \`${host}:${port}\`.`, ephemeral: true });
}

async function handleAfk(interaction) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply();
  const name = safeFarmName(interaction.options.getString('farm', true));
  const durationMs = parseDuration(interaction.options.getString('time'));
  const store = await loadStore();
  if (!store.farms[name]) {
    await interaction.editReply(`No farm named \`${name}\`.`);
    return;
  }
  await resolveMinecraftName(name, store.farms[name]);
  await interaction.editReply(spawnFarm(name, store.farms[name], interaction, durationMs).message);
}

async function handleAfkAll(interaction) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply();
  const store = await loadStore();
  const names = Object.keys(store.farms);
  if (!names.length) {
    await interaction.editReply('No farms saved.');
    return;
  }
  const messages = [];
  for (const name of names) {
    await resolveMinecraftName(name, store.farms[name]);
    messages.push(spawnFarm(name, store.farms[name], interaction).message);
  }
  await interaction.editReply(messages.join('\n'));
}

async function resumeReconnectFarms(client) {
  const store = await loadStore();
  const globalRejoin = store.config?.rejoin;
  const owners = await loadOwners();
  const ownerId = owners[0];
  if (!ownerId) {
    console.warn('No owner configured; not auto-resuming AFK farms.');
    return;
  }

  const serviceContext = {
    user: { id: ownerId },
    channelId: null,
    channel: null,
    client,
    followUp: async () => {}
  };

  for (const [name, farm] of Object.entries(store.farms || {})) {
    const shouldRejoin = typeof globalRejoin === 'boolean' ? globalRejoin : farm.reconnect !== false;
    if (!shouldRejoin) continue;
    await resolveMinecraftName(name, farm);
    const result = spawnFarm(name, farm, serviceContext);
    console.log(`[${name}] auto-resume: ${result.message}`);
  }
}

async function handleConfig(interaction) {
  if (!(await requireOwner(interaction))) return;
  const rejoin = interaction.options.getBoolean('rejoin', true);
  const store = await loadStore();
  store.config = {
    ...(store.config || {}),
    rejoin,
    updatedAt: new Date().toISOString()
  };
  for (const farm of Object.values(store.farms || {})) {
    farm.reconnect = rejoin;
    farm.updatedAt = new Date().toISOString();
  }
  await saveStore(store);
  for (const session of running.values()) {
    session.farm.reconnect = rejoin;
  }
  await interaction.reply({ content: `AFK rejoin is now \`${rejoin}\`.`, ephemeral: true });
}

async function handleFarms(interaction) {
  if (!(await requireOwner(interaction))) return;
  const store = await loadStore();
  const names = Object.keys(store.farms);
  const configLine = `config: rejoin=\`${store.config?.rejoin ?? 'per-farm'}\``;
  await interaction.reply({
    content: names.length ? `${configLine}\n${names.map((name) => formatFarm(name, store.farms[name])).join('\n')}` : `${configLine}\nNo farms saved.`,
    ephemeral: true
  });
}

async function handleStop(interaction) {
  if (!(await requireOwner(interaction))) return;
  const name = safeFarmName(interaction.options.getString('farm', true));
  const session = running.get(name);
  if (!session) {
    await interaction.reply({ content: `\`${name}\` is not running.`, ephemeral: true });
    return;
  }
  stopRunningFarm(name, 'manual');
  await interaction.reply({ content: `${publicAccountName(session.farm)} is no longer AFK at \`${name}\`.` });
}

async function handleStopAll(interaction) {
  if (!(await requireOwner(interaction))) return;
  const sessions = [...running.entries()];
  for (const [name] of sessions) stopRunningFarm(name, 'manual');
  await interaction.reply({
    content: sessions.length ? sessions.map(([name, session]) => `${publicAccountName(session.farm)} is no longer AFK at \`${name}\`.`).join('\n') : 'Nothing was running.'
  });
}

async function handleRemove(interaction) {
  if (!(await requireOwner(interaction))) return;
  const name = safeFarmName(interaction.options.getString('farm', true));
  const store = await loadStore();
  if (!store.farms[name]) {
    await interaction.reply({ content: `No farm named \`${name}\`.`, ephemeral: true });
    return;
  }
  delete store.farms[name];
  await saveStore(store);
  await interaction.reply({ content: `Removed \`${name}\`. Auth cache was left alone.`, ephemeral: true });
}

async function handleOwners(interaction) {
  if (!(await requireOwner(interaction))) return;
  const owners = await loadOwners();
  await interaction.reply({
    content: owners.length ? owners.map((id) => `<@${id}> (${id})`).join('\n') : 'No owners configured.',
    ephemeral: true
  });
}

function forceTaskPlanBot(plan, botName) {
  return {
    ...plan,
    actions: (plan.actions || []).map((action) => (
      action.type === 'status' ? action : { ...action, bot: botName }
    ))
  };
}

async function handleTaskBot(interaction, taskManager, forcedBot = null) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply();
  const prompt = clean(interaction.options.getString('prompt', true));
  const rawPlan = await planTask(prompt);
  const plan = forcedBot ? forceTaskPlanBot(rawPlan, forcedBot) : rawPlan;
  const result = await taskManager.executeTaskBotPlan(plan, {
    notifyChannelId: interaction.channelId,
    notifyUserId: interaction.user.id
  });
  const actions = (plan.actions || []).map((action) => `\`${action.type}\``).join(', ');
  const botLine = forcedBot ? `Bot: \`${forcedBot}\`\n` : '';
  await interaction.editReply(`${botLine}Plan: ${plan.summary}\nActions: ${actions}\n${result}`);
}

async function handleTaskBotSet(interaction, taskManager) {
  if (!(await requireOwner(interaction))) return;
  const bot = await taskManager.setBot({
    name: interaction.options.getString('name', true),
    account: interaction.options.getString('account', true),
    host: interaction.options.getString('host', true),
    port: interaction.options.getInteger('port') || 25565,
    profile: interaction.options.getString('profile') || '',
    instance: interaction.options.getString('instance') || ''
  });
  await interaction.reply({
    content: `Saved task bot \`${bot.name}\` for \`${bot.host}:${bot.port}\`. Use \`/tasklaunch name:${bot.name}\`.`,
    ephemeral: true
  });
}

async function handleTaskLaunch(interaction, taskManager) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply();
  const name = interaction.options.getString('name', true);
  const message = await taskManager.ensureNativeTaskClient(name);
  await interaction.editReply(message);
}

async function handleToolDurability(interaction, taskManager) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply({ ephemeral: true });
  const report = await taskManager.nativeToolDurability(interaction.options.getString('name') || 'kira');
  await interaction.editReply(report);
}

async function handleToolRestock(interaction, taskManager) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply();
  const message = await taskManager.nativeToolRestock(interaction.options.getString('name') || 'kira');
  await interaction.editReply(message);
}

async function handleTaskRejoin(interaction, taskManager) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply();
  const message = await taskManager.nativeTaskRejoin(interaction.options.getString('name') || 'kira', { resume: true });
  await interaction.editReply(message);
}

async function handleReset(interaction, taskManager) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply();
  const message = await taskManager.resetNativeTaskBots({ names: ['kira', 'azure'], resume: true });
  await interaction.editReply(message);
}

async function handleReset1(interaction, taskManager) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply();
  const message = await taskManager.resetNativeTaskBot(interaction.options.getString('name', true), { resume: true });
  await interaction.editReply(message);
}

async function handleInspect(interaction) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply({ ephemeral: true });
  const project = await analyzeProjectForAssistant();
  const summary = [
    `Project root: ${rootDir}`,
    `Files indexed: ${project.fileCount}`,
    `Key folders: ${project.folders.join(', ') || 'none'}`,
    `Issues: ${project.issues.length ? project.issues.join(' | ') : 'none detected by heuristics'}`
  ].join('\n');
  const ai = await assistantPromptAnalysis({
    command: 'inspect',
    targetPath: '',
    instruction: 'Analyze the project structure and surface likely issues.',
    extra: summary
  });
  await interaction.editReply(clipDiscord(`${assistantHeader('Project inspection', [summary])}\n\nGroq analysis:\n${ai.analysis}`));
}

async function handleReadFile(interaction) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply({ ephemeral: true });
  const targetPath = interaction.options.getString('path', true);
  const { filePath, content } = await projectEditor.readFile(targetPath);
  await interaction.editReply(clipDiscord(`File: \`${path.relative(rootDir, filePath)}\`\n\n\`\`\`text\n${readPreview(content)}\n\`\`\``));
}

async function handleSearch(interaction) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply({ ephemeral: true });
  const query = interaction.options.getString('query', true);
  const results = await projectEditor.search(query);
  const lines = results.length
    ? results.map((hit) => `\`${hit.file}:${hit.line}\` ${hit.text}`).join('\n')
    : 'No matches.';
  await interaction.editReply(clipDiscord(`Search: \`${query}\`\n\n${lines}`));
}

async function handleEdit(interaction) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply({ ephemeral: true });
  const targetPath = interaction.options.getString('path', true);
  const content = interaction.options.getString('content', true);
  const apply = interaction.options.getBoolean('apply') || false;
  const existing = await projectEditor.readFile(targetPath).catch(() => null);
  const analysis = await assistantPromptAnalysis({
    command: 'edit',
    targetPath,
    instruction: 'Replace the file content with the provided content using the minimal necessary change.',
    content: existing?.content || '',
    extra: `New content length: ${content.length}`
  });
  const result = await buildAssistantResult({
    command: 'edit',
    targetPath,
    apply,
    files: [targetPath],
    intent: analysis.intent,
    modify: async () => {
      const write = await projectEditor.writeFile(targetPath, content);
      return `Backed up to ${path.relative(rootDir, write.backup)} and wrote ${path.relative(rootDir, write.filePath)}.`;
    }
  });
  await interaction.editReply(clipDiscord(`${assistantHeader('Edit proposal', [analysis.analysis])}\n\n${result}`));
}

async function handleCreateFile(interaction) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply({ ephemeral: true });
  const targetPath = interaction.options.getString('path', true);
  const content = interaction.options.getString('content', true);
  const apply = interaction.options.getBoolean('apply') || false;
  const analysis = await assistantPromptAnalysis({
    command: 'createfile',
    targetPath,
    instruction: 'Create a new file with the provided content and keep scope minimal.',
    extra: `New content length: ${content.length}`
  });
  const result = await buildAssistantResult({
    command: 'createfile',
    targetPath,
    apply,
    files: [targetPath],
    intent: analysis.intent,
    modify: async () => {
      const write = await projectEditor.writeFile(targetPath, content);
      return `Created ${path.relative(rootDir, write.filePath)} with backup ${path.relative(rootDir, write.backup)}.`;
    }
  });
  await interaction.editReply(clipDiscord(`${assistantHeader('Create proposal', [analysis.analysis])}\n\n${result}`));
}

async function handleDeleteFile(interaction) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply({ ephemeral: true });
  const targetPath = interaction.options.getString('path', true);
  const confirm = interaction.options.getBoolean('confirm') || false;
  const analysis = await assistantPromptAnalysis({
    command: 'deletefile',
    targetPath,
    instruction: 'Delete the requested file only if explicitly confirmed.',
  });
  const result = await buildAssistantResult({
    command: 'deletefile',
    targetPath,
    apply: confirm,
    files: [targetPath],
    intent: analysis.intent,
    modify: async () => {
      const deleted = await projectEditor.deleteFile(targetPath);
      return `Deleted ${path.relative(rootDir, deleted.filePath)} with backup stored.`;
    }
  });
  await interaction.editReply(clipDiscord(`${assistantHeader('Delete proposal', [analysis.analysis])}\n\n${result}`));
}

async function handleRefactor(interaction) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply({ ephemeral: true });
  const targetPath = interaction.options.getString('path', true);
  const instruction = interaction.options.getString('instruction', true);
  const apply = interaction.options.getBoolean('apply') || false;
  const { content } = await projectEditor.readFile(targetPath).catch(() => ({ content: '' }));
  const analysis = await assistantPromptAnalysis({
    command: 'refactor',
    targetPath,
    instruction,
    content,
    extra: 'Produce a minimal refactor that preserves behavior.'
  });
  await interaction.editReply(clipDiscord(`${assistantHeader('Refactor plan', [analysis.analysis])}\n\n${formatAssistantPlan({ command: 'refactor', path: targetPath, files: [targetPath], intent: instruction, apply })}`));
}

async function handleFix(interaction) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply({ ephemeral: true });
  const targetPath = interaction.options.getString('path', true);
  const symptom = interaction.options.getString('symptom', true);
  const apply = interaction.options.getBoolean('apply') || false;
  const { content } = await projectEditor.readFile(targetPath).catch(() => ({ content: '' }));
  const analysis = await assistantPromptAnalysis({
    command: 'fix',
    targetPath,
    instruction: symptom,
    content,
    extra: 'Identify the likely bug and propose the smallest safe repair.'
  });
  await interaction.editReply(clipDiscord(`${assistantHeader('Fix plan', [analysis.analysis])}\n\n${formatAssistantPlan({ command: 'fix', path: targetPath, files: [targetPath], intent: symptom, apply })}`));
}

async function handleCommit(interaction) {
  if (!(await requireOwner(interaction))) return;
  await interaction.deferReply({ ephemeral: true });
  const recent = await fs.readFile(path.join(rootDir, '.assistant-backups', 'changes.jsonl'), 'utf8').catch(() => '');
  const lines = recent.trim().split(/\r?\n/).filter(Boolean).slice(-10).map((line) => JSON.parse(line));
  const summary = lines.length
    ? lines.map((entry) => `\`${entry.file}\` backup=${path.basename(entry.backupFile)}`).join('\n')
    : 'No assistant edits recorded yet.';
  const ai = await assistantPromptAnalysis({
    command: 'commit',
    instruction: 'Generate a compact change summary from the recent assistant edits.',
    extra: summary
  });
  await interaction.editReply(clipDiscord(`Change summary:\n${summary}\n\nGroq summary:\n${ai.analysis}`));
}

async function main() {
  const token = process.env.DISCORD_TOKEN;
  if (!token) throw new Error('DISCORD_TOKEN is required.');

  const client = new Client({ intents: [] });
  const taskManager = new TaskManager({ discordClient: client });

  client.once(Events.ClientReady, (readyClient) => {
    console.log(`Discord AFK manager logged in as ${readyClient.user.tag}`);
    void taskManager.start().catch((error) => {
      console.error(`Failed to start task manager: ${error.message}`);
    });
    void resumeReconnectFarms(readyClient).catch((error) => {
      console.error(`Failed to auto-resume AFK farms: ${error.message}`);
    });
  });

  client.on(Events.InteractionCreate, async (interaction) => {
    if (!interaction.isChatInputCommand()) return;
    try {
      switch (interaction.commandName) {
        case 'claim-owner':
          await handleClaimOwner(interaction);
          break;
        case 'afkset':
          await handleAfkSet(interaction);
          break;
        case 'afk':
          await handleAfk(interaction);
          break;
        case 'afkall':
          await handleAfkAll(interaction);
          break;
        case 'config':
          await handleConfig(interaction);
          break;
        case 'farms':
          await handleFarms(interaction);
          break;
        case 'stop':
          await handleStop(interaction);
          break;
        case 'stopall':
          await handleStopAll(interaction);
          break;
        case 'remove':
          await handleRemove(interaction);
          break;
        case 'taskbot':
          await handleTaskBot(interaction, taskManager);
          break;
        case 'taskkira':
          await handleTaskBot(interaction, taskManager, 'kira');
          break;
        case 'taskazure':
          await handleTaskBot(interaction, taskManager, 'azure');
          break;
        case 'taskbotset':
          await handleTaskBotSet(interaction, taskManager);
          break;
        case 'tasklaunch':
          await handleTaskLaunch(interaction, taskManager);
          break;
        case 'tooldurability':
          await handleToolDurability(interaction, taskManager);
          break;
        case 'toolrestock':
          await handleToolRestock(interaction, taskManager);
          break;
        case 'taskrejoin':
          await handleTaskRejoin(interaction, taskManager);
          break;
        case 'reset':
          await handleReset(interaction, taskManager);
          break;
        case 'reset1':
          await handleReset1(interaction, taskManager);
          break;
        case 'owners':
          await handleOwners(interaction);
          break;
        case 'inspect':
          await handleInspect(interaction);
          break;
        case 'readfile':
          await handleReadFile(interaction);
          break;
        case 'search':
          await handleSearch(interaction);
          break;
        case 'edit':
          await handleEdit(interaction);
          break;
        case 'createfile':
          await handleCreateFile(interaction);
          break;
        case 'deletefile':
          await handleDeleteFile(interaction);
          break;
        case 'refactor':
          await handleRefactor(interaction);
          break;
        case 'fix':
          await handleFix(interaction);
          break;
        case 'commit':
          await handleCommit(interaction);
          break;
      }
    } catch (error) {
      console.error(error);
      const content = `Command failed: ${error.message}`;
      if (interaction.deferred || interaction.replied) await interaction.followUp({ content, ephemeral: true });
      else await interaction.reply({ content, ephemeral: true });
    }
  });

  process.on('SIGTERM', () => {
    for (const name of running.keys()) stopRunningFarm(name, 'shutdown');
    void taskManager.stop().catch(() => {});
    process.exit(0);
  });

  await client.login(token);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
