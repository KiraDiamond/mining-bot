require('dotenv').config();

const { REST, Routes } = require('discord.js');
const { commands } = require('./discord-common');

async function main() {
  const token = process.env.DISCORD_TOKEN;
  const clientId = process.env.DISCORD_CLIENT_ID;
  if (!token || !clientId) throw new Error('DISCORD_TOKEN and DISCORD_CLIENT_ID are required.');

  const rest = new REST({ version: '10' }).setToken(token);
  await rest.put(Routes.applicationCommands(clientId), { body: commands });
  console.log(`Registered ${commands.length} global commands.`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
