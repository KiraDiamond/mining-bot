const {
  ApplicationCommandOptionType,
  ApplicationIntegrationType,
  InteractionContextType
} = require('discord.js');

const integrationTypes = [
  ApplicationIntegrationType.GuildInstall,
  ApplicationIntegrationType.UserInstall
];

const contexts = [
  InteractionContextType.Guild,
  InteractionContextType.BotDM,
  InteractionContextType.PrivateChannel
];

const commands = [
  {
    name: 'claim-owner',
    description: 'Claim ownership of this AFK controller if no owner exists.'
  },
  {
    name: 'afkset',
    description: 'Create or update a farm account profile.',
    options: [
      { name: 'farm', description: 'Farm name, for example iron.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'account', description: 'Microsoft account email or offline username.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'host', description: 'Minecraft server host.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'port', description: 'Minecraft server port.', type: ApplicationCommandOptionType.Integer, required: false },
      { name: 'version', description: 'Protocol version. Use auto unless needed.', type: ApplicationCommandOptionType.String, required: false },
      {
        name: 'auth',
        description: 'Authentication mode.',
        type: ApplicationCommandOptionType.String,
        required: false,
        choices: [
          { name: 'microsoft', value: 'microsoft' },
          { name: 'offline', value: 'offline' }
        ]
      }
    ]
  },
  {
    name: 'afk',
    description: 'Start one farm account.',
    options: [
      { name: 'farm', description: 'Farm name.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'time', description: 'Optional AFK time, for example 30m, 4h, 1h30m, or 2d.', type: ApplicationCommandOptionType.String, required: false }
    ]
  },
  { name: 'afkall', description: 'Start every saved farm account.' },
  {
    name: 'config',
    description: 'Configure AFK manager behavior.',
    options: [
      { name: 'rejoin', description: 'If true, AFK bots always reconnect after disconnect/crash.', type: ApplicationCommandOptionType.Boolean, required: true }
    ]
  },
  { name: 'farms', description: 'List saved farms and running state.' },
  {
    name: 'stop',
    description: 'Stop one running farm account.',
    options: [
      { name: 'farm', description: 'Farm name.', type: ApplicationCommandOptionType.String, required: true }
    ]
  },
  { name: 'stopall', description: 'Stop all running farm accounts.' },
  {
    name: 'remove',
    description: 'Remove a farm profile. Auth cache is left alone.',
    options: [
      { name: 'farm', description: 'Farm name.', type: ApplicationCommandOptionType.String, required: true }
    ]
  },
  {
    name: 'taskbot',
    description: 'Tell the Minecraft task bot what to do in plain English.',
    options: [
      {
        name: 'prompt',
        description: 'Example: login and mine out -113 63 14 -200 140 -200',
        type: ApplicationCommandOptionType.String,
        required: true
      }
    ]
  },
  {
    name: 'taskkira',
    description: 'Tell only Kira what to do.',
    options: [
      {
        name: 'prompt',
        description: 'Example: mine out 25 76 86 -21 58 66',
        type: ApplicationCommandOptionType.String,
        required: true
      }
    ]
  },
  {
    name: 'taskazure',
    description: 'Tell only Azure what to do.',
    options: [
      {
        name: 'prompt',
        description: 'Example: mine out 25 76 106 -21 58 87',
        type: ApplicationCommandOptionType.String,
        required: true
      }
    ]
  },
  {
    name: 'taskbotset',
    description: 'Create or update a native Baritone task bot.',
    options: [
      { name: 'name', description: 'Bot name, for example sasuo.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'account', description: 'Minecraft account email, for your notes only.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'host', description: 'Minecraft server host.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'port', description: 'Minecraft server port.', type: ApplicationCommandOptionType.Integer, required: false },
      { name: 'profile', description: 'Prism account/profile name if needed.', type: ApplicationCommandOptionType.String, required: false },
      { name: 'instance', description: 'Optional Prism instance folder. Default auto-creates one.', type: ApplicationCommandOptionType.String, required: false }
    ]
  },
  {
    name: 'tasklaunch',
    description: 'Launch one native Baritone task bot.',
    options: [
      { name: 'name', description: 'Bot name.', type: ApplicationCommandOptionType.String, required: true }
    ]
  },
  {
    name: 'tooldurability',
    description: 'Show task bot armor and tool durability.',
    options: [
      { name: 'name', description: 'Bot name. Defaults to kira.', type: ApplicationCommandOptionType.String, required: false }
    ]
  },
  {
    name: 'toolrestock',
    description: 'Send task bot to the tool chest to pick repaired tools back up.',
    options: [
      { name: 'name', description: 'Bot name. Defaults to kira.', type: ApplicationCommandOptionType.String, required: false }
    ]
  },
  {
    name: 'taskrejoin',
    description: 'Restart a native Baritone task bot and resume its last task.',
    options: [
      { name: 'name', description: 'Bot name. Defaults to kira.', type: ApplicationCommandOptionType.String, required: false }
    ]
  },
  {
    name: 'reset',
    description: 'Reset Kira and Azure native Baritone clients, relaunch them, and resume their saved split tasks.'
  },
  {
    name: 'reset1',
    description: 'Reset one native Baritone client and resume its saved task.',
    options: [
      { name: 'name', description: 'Bot name, for example kira or azure.', type: ApplicationCommandOptionType.String, required: true }
    ]
  },
  { name: 'owners', description: 'Show configured controller owners.' }
  ,
  {
    name: 'inspect',
    description: 'Analyze the project structure and report issues.'
  },
  {
    name: 'readfile',
    description: 'Read a file from the project.',
    options: [
      { name: 'path', description: 'Relative project path.', type: ApplicationCommandOptionType.String, required: true }
    ]
  },
  {
    name: 'search',
    description: 'Search the codebase for a pattern.',
    options: [
      { name: 'query', description: 'Text or regex fragment to search for.', type: ApplicationCommandOptionType.String, required: true }
    ]
  },
  {
    name: 'edit',
    description: 'Propose or apply a file content change.',
    options: [
      { name: 'path', description: 'Relative project path.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'content', description: 'Replacement file content.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'apply', description: 'Set true to apply the edit.', type: ApplicationCommandOptionType.Boolean, required: false }
    ]
  },
  {
    name: 'createfile',
    description: 'Propose or apply a new file.',
    options: [
      { name: 'path', description: 'Relative project path.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'content', description: 'File contents.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'apply', description: 'Set true to create the file.', type: ApplicationCommandOptionType.Boolean, required: false }
    ]
  },
  {
    name: 'deletefile',
    description: 'Propose or delete a file after confirmation.',
    options: [
      { name: 'path', description: 'Relative project path.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'confirm', description: 'Set true to delete the file.', type: ApplicationCommandOptionType.Boolean, required: false }
    ]
  },
  {
    name: 'refactor',
    description: 'Analyze a file or area and propose a refactor.',
    options: [
      { name: 'path', description: 'Relative project path or directory.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'instruction', description: 'Refactor goal.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'apply', description: 'Set true to apply the refactor.', type: ApplicationCommandOptionType.Boolean, required: false }
    ]
  },
  {
    name: 'fix',
    description: 'Analyze and repair a bug in a file or area.',
    options: [
      { name: 'path', description: 'Relative project path or directory.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'symptom', description: 'Bug description.', type: ApplicationCommandOptionType.String, required: true },
      { name: 'apply', description: 'Set true to apply the fix.', type: ApplicationCommandOptionType.Boolean, required: false }
    ]
  },
  {
    name: 'commit',
    description: 'Generate a concise change summary.'
  }
].map((command) => ({
  ...command,
  integration_types: integrationTypes,
  contexts
}));

module.exports = { commands };
