require('dotenv').config();

const { Client, Events } = require('discord.js');
const { TaskManager } = require('./task-manager');

const OWNER_ID = process.env.OWNER_IDS?.split(',').map((id) => id.trim()).filter(Boolean)[0];
const BOT_NAME = process.env.TASK_BOT_NAME || 'kira';
const CUBOID = {
  x1: -113,
  y1: 63,
  z1: 14,
  x2: -240,
  y2: 110,
  z2: -250
};

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForBotInWorld(taskManager, name, timeoutMs = 180000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const bot = (await taskManager.listBots()).find((entry) => entry.name === name);
    if (bot?.connected && bot.status?.position && bot.username && bot.username !== 'not_joined') return bot;
    await sleep(2000);
  }
  throw new Error(`Timed out waiting for ${name} to connect to the control mod in-world.`);
}

async function dm(client, content) {
  if (!OWNER_ID) return;
  const clipped = content.length > 1900 ? `${content.slice(0, 1900)}...` : content;
  try {
    const user = await client.users.fetch(OWNER_ID);
    await user.send(clipped);
  } catch (error) {
    console.error(`Failed to DM owner: ${error.message}`);
  }
}

async function main() {
  if (!process.env.DISCORD_TOKEN) throw new Error('DISCORD_TOKEN is required.');
  if (!OWNER_ID) throw new Error('OWNER_IDS is required.');

  const client = new Client({ intents: [] });
  const taskManager = new TaskManager({ discordClient: client });
  await taskManager.start();

  client.once(Events.ClientReady, async (readyClient) => {
    console.log(`Discord runner logged in as ${readyClient.user.tag}`);
    try {
      const launched = await taskManager.launchBot(BOT_NAME, {
        onEvent: (message) => void dm(client, message),
        onLoginHint: (message) => void dm(client, `${BOT_NAME} login hint:\n${message}`)
      });
      console.log(launched.message);
      await dm(client, launched.message);

      const bot = await waitForBotInWorld(taskManager, BOT_NAME);
      await dm(client, `\`${bot.username}\` connected. Starting cuboid clear -113 63 14 -> -240 110 -250.`);

      const result = await taskManager.mineCuboid({
        ...CUBOID,
        bots: BOT_NAME,
        notifyUserId: OWNER_ID
      });
      console.log(result.message);
      await dm(client, result.message);
    } catch (error) {
      console.error(error);
      await dm(client, `Task runner failed: ${error.message}`);
    }
  });

  process.on('SIGTERM', () => {
    void taskManager.stop().finally(() => process.exit(0));
  });

  await client.login(process.env.DISCORD_TOKEN);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
