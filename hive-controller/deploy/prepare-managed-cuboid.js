const crypto = require('crypto');
const fs = require('fs/promises');
const path = require('path');
const { loadTaskStore, dataDir } = require('../src/task-store');
const {
  normalizeCuboid,
  assertOutsideNoZones,
  splitCuboidByLongestHorizontal,
  safeName
} = require('../src/task-utils');

async function main() {
  const [x1, y1, z1, x2, y2, z2, botsArg = 'kira,azure'] = process.argv.slice(2);
  const cuboid = normalizeCuboid({ x1, y1, z1, x2, y2, z2 });
  const store = await loadTaskStore();
  assertOutsideNoZones(cuboid, store.noZones);

  const configured = new Set(Object.keys(store.bots || {}).map(safeName));
  const bots = botsArg.split(',').map(safeName).filter(Boolean);
  const unknown = bots.filter((bot) => !configured.has(bot));
  if (unknown.length) throw new Error(`Unknown task bot(s): ${unknown.join(', ')}`);

  const taskId = crypto.randomUUID();
  const minDurability = Math.max(10, store.settings?.minDurability || 10);
  const slices = splitCuboidByLongestHorizontal(cuboid, bots);
  for (const slice of slices) {
    const payload = {
      id: taskId,
      kind: 'mine-cuboid',
      cuboid: slice.cuboid,
      originalCuboid: cuboid,
      noZones: (store.noZones || []).filter((zone) => !zone.disabled),
      minDurability
    };
    const task = {
      taskId,
      kind: 'mine-cuboid',
      cuboid: slice.cuboid,
      payload,
      active: true,
      notifyChannelId: null,
      notifyUserId: null,
      preparedAt: new Date().toISOString()
    };
    const target = path.join(dataDir, `native-task-${slice.bot}-active.json`);
    await fs.writeFile(target, JSON.stringify({ bot: slice.bot, task }, null, 2), 'utf8');
  }

  console.log(JSON.stringify({ taskId, slices }, null, 2));
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
