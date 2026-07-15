const fs = require('fs/promises');
const path = require('path');
const { DEFAULT_WORLD_SEED } = require('./task-utils');

const rootDir = path.resolve(__dirname, '..');
const dataDir = path.join(rootDir, 'data');
const storePath = path.join(dataDir, 'task-bots.json');

async function ensureDataDir() {
  await fs.mkdir(dataDir, { recursive: true });
}

function defaultStore() {
  return {
    settings: {
      seed: DEFAULT_WORLD_SEED,
      controlHost: '127.0.0.1',
      controlPort: 47391,
      minDurability: 5
    },
    bots: {},
    noZones: []
  };
}

async function loadTaskStore() {
  await ensureDataDir();
  try {
    const parsed = JSON.parse(await fs.readFile(storePath, 'utf8'));
    return {
      ...defaultStore(),
      ...parsed,
      settings: { ...defaultStore().settings, ...(parsed.settings || {}) },
      bots: parsed.bots || {},
      noZones: parsed.noZones || []
    };
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    return defaultStore();
  }
}

async function saveTaskStore(store) {
  await ensureDataDir();
  await fs.writeFile(storePath, JSON.stringify(store, null, 2));
}

module.exports = {
  dataDir,
  storePath,
  defaultStore,
  loadTaskStore,
  saveTaskStore
};
