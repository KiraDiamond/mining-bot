require('dotenv').config();

const readline = require('readline/promises');
const { stdin: input, stdout: output } = require('process');
const { TaskManager } = require('./task-manager');
const { loadTaskStore } = require('./task-store');
const { formatCuboid } = require('./task-utils');

function parseArgs(text) {
  const args = [];
  const re = /"([^"]*)"|'([^']*)'|(\S+)/g;
  let match;
  while ((match = re.exec(text)) !== null) args.push(match[1] ?? match[2] ?? match[3]);
  return args;
}

function usage() {
  return `
Commands:
  help
  bot set <name> <account> <host> [port] --cmd "<launch command>"
  bot list
  launch <name>
  kill <name>
  status
  goto <name> <x> <y> <z>
  clear <x1> <y1> <z1> <x2> <y2> <z2> [bots]
  mine <resource> <amount> [bots]
  nozone add <name> <x1> <y1> <z1> <x2> <y2> <z2>
  nozone list
  nozone remove <name>
  stop [bots]
  quit

Launch command placeholders:
  {bot} {account} {host} {port} {server} {controlHost} {controlPort} {token}

Example:
  bot set kira kira@example.com 209.25.141.24 1306 --cmd "xvfb-run -a ./launch-task-client.sh --server {server}"
  launch kira
  goto kira 22 63 -192
  clear -113 63 14 -240 110 -250 all
`.trim();
}

function required(args, count, message) {
  if (args.length < count) throw new Error(message);
}

function integer(value, label) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed)) throw new Error(`${label} must be an integer.`);
  return parsed;
}

function launchLogger(message) {
  console.log(message);
}

async function handleBot(args, manager) {
  const subcommand = args.shift();
  if (subcommand === 'set') {
    const cmdIndex = args.indexOf('--cmd');
    const launchCommand = cmdIndex >= 0 ? args.slice(cmdIndex + 1).join(' ') : '';
    const botArgs = cmdIndex >= 0 ? args.slice(0, cmdIndex) : args;
    required(botArgs, 3, 'Usage: bot set <name> <account> <host> [port] --cmd "<launch command>"');
    const [name, account, host, port = '25565'] = botArgs;
    const bot = await manager.setBot({ name, account, host, port, launchCommand });
    console.log(`Saved ${bot.name} -> ${bot.host}:${bot.port}${bot.launchCommand ? ' with launch command.' : ' without launch command.'}`);
    return;
  }

  if (subcommand === 'list') {
    const bots = await manager.listBots();
    if (!bots.length) {
      console.log('No task bots saved.');
      return;
    }
    for (const bot of bots) {
      console.log(`${bot.name}: ${bot.username || bot.name} | ${bot.host}:${bot.port} | ${bot.connected ? 'connected' : bot.launched ? 'launched' : 'stopped'} | ${bot.backend}`);
    }
    return;
  }

  throw new Error('Usage: bot set ... OR bot list');
}

async function handleNoZone(args, manager) {
  const subcommand = args.shift();
  if (subcommand === 'add') {
    required(args, 7, 'Usage: nozone add <name> <x1> <y1> <z1> <x2> <y2> <z2>');
    const [name, x1, y1, z1, x2, y2, z2] = args;
    const zone = await manager.addNoZone({ name, x1, y1, z1, x2, y2, z2 });
    console.log(`Saved no-mine zone ${zone.name}: ${formatCuboid(zone.cuboid)}.`);
    return;
  }

  if (subcommand === 'list') {
    const zones = await manager.listNoZones();
    if (!zones.length) {
      console.log('No active no-mine zones.');
      return;
    }
    for (const zone of zones) console.log(`${zone.name}: ${formatCuboid(zone.cuboid)}`);
    return;
  }

  if (subcommand === 'remove') {
    required(args, 1, 'Usage: nozone remove <name>');
    const removed = await manager.archiveNoZone(args[0]);
    console.log(removed ? `Disabled no-mine zone ${args[0]}.` : `No active no-mine zone named ${args[0]}.`);
    return;
  }

  throw new Error('Usage: nozone add/list/remove');
}

async function dispatch(line, manager) {
  const args = parseArgs(line);
  const command = (args.shift() || '').toLowerCase();
  if (!command) return true;

  switch (command) {
    case 'help':
      console.log(usage());
      return true;
    case 'bot':
      await handleBot(args, manager);
      return true;
    case 'launch': {
      required(args, 1, 'Usage: launch <name>');
      const result = await manager.launchBot(args[0], { onEvent: launchLogger, onLoginHint: launchLogger });
      console.log(result.message);
      return true;
    }
    case 'kill': {
      required(args, 1, 'Usage: kill <name>');
      console.log(manager.stopLaunch(args[0]) ? `Killed ${args[0]}.` : `${args[0]} was not launched.`);
      return true;
    }
    case 'status':
      console.log(manager.taskStatus());
      return true;
    case 'goto': {
      required(args, 4, 'Usage: goto <name> <x> <y> <z>');
      const [name, x, y, z] = args;
      const result = await manager.gotoBot(name, integer(x, 'x'), integer(y, 'y'), integer(z, 'z'));
      console.log(result.message);
      return true;
    }
    case 'clear': {
      required(args, 6, 'Usage: clear <x1> <y1> <z1> <x2> <y2> <z2> [bots]');
      const [x1, y1, z1, x2, y2, z2, bots = 'all'] = args;
      const result = await manager.mineCuboid({ x1, y1, z1, x2, y2, z2, bots });
      console.log(result.message);
      return true;
    }
    case 'mine': {
      required(args, 2, 'Usage: mine <resource> <amount> [bots]');
      const [resource, amount, bots = 'all'] = args;
      const result = await manager.mineResource({ resource, amount, bots });
      console.log(result.message);
      return true;
    }
    case 'nozone':
      await handleNoZone(args, manager);
      return true;
    case 'stop': {
      const stopped = manager.stopTask(args[0] || 'all');
      console.log(stopped.length ? `Stopped task work on ${stopped.join(', ')}.` : 'No connected task bots matched.');
      return true;
    }
    case 'store': {
      console.log(JSON.stringify(await loadTaskStore(), null, 2));
      return true;
    }
    case 'quit':
    case 'exit':
      return false;
    default:
      throw new Error(`Unknown command "${command}". Type "help".`);
  }
}

async function main() {
  const manager = new TaskManager();
  await manager.start();
  const rl = readline.createInterface({ input, output, prompt: 'task> ' });

  console.log('Task console ready. Type "help".');
  rl.prompt();
  for await (const line of rl) {
    try {
      const keepRunning = await dispatch(line, manager);
      if (!keepRunning) break;
    } catch (error) {
      console.error(`Error: ${error.message}`);
    }
    rl.prompt();
  }

  rl.close();
  await manager.stop();
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
