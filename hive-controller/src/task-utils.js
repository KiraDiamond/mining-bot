const DEFAULT_WORLD_SEED = '4145193711797456157';

function clean(value) {
  return String(value ?? '').trim();
}

function safeName(value) {
  return clean(value).toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 48);
}

function asInteger(value, label) {
  const number = Number(value);
  if (!Number.isInteger(number)) throw new Error(`${label} must be an integer.`);
  return number;
}

function normalizeCuboid(input) {
  const x1 = asInteger(input.x1, 'x1');
  const y1 = asInteger(input.y1, 'y1');
  const z1 = asInteger(input.z1, 'z1');
  const x2 = asInteger(input.x2, 'x2');
  const y2 = asInteger(input.y2, 'y2');
  const z2 = asInteger(input.z2, 'z2');
  return {
    x1: Math.min(x1, x2),
    y1: Math.min(y1, y2),
    z1: Math.min(z1, z2),
    x2: Math.max(x1, x2),
    y2: Math.max(y1, y2),
    z2: Math.max(z1, z2)
  };
}

function cuboidVolume(cuboid) {
  const c = normalizeCuboid(cuboid);
  return (c.x2 - c.x1 + 1) * (c.y2 - c.y1 + 1) * (c.z2 - c.z1 + 1);
}

function cuboidsIntersect(a, b) {
  const left = normalizeCuboid(a);
  const right = normalizeCuboid(b);
  return left.x1 <= right.x2 && left.x2 >= right.x1
    && left.y1 <= right.y2 && left.y2 >= right.y1
    && left.z1 <= right.z2 && left.z2 >= right.z1;
}

function pointInCuboid(point, cuboid) {
  const c = normalizeCuboid(cuboid);
  return point.x >= c.x1 && point.x <= c.x2
    && point.y >= c.y1 && point.y <= c.y2
    && point.z >= c.z1 && point.z <= c.z2;
}

function assertOutsideNoZones(cuboid, noZones) {
  const active = (noZones || []).filter((zone) => !zone.disabled);
  const hit = active.find((zone) => cuboidsIntersect(cuboid, zone.cuboid || zone));
  if (hit) throw new Error(`Target intersects no-mine zone "${hit.name || 'unnamed'}".`);
}

function splitCuboidByY(cuboid, botNames) {
  const c = normalizeCuboid(cuboid);
  const names = [...new Set((botNames || []).map(safeName).filter(Boolean))];
  if (!names.length) throw new Error('At least one bot is required.');

  const height = c.y2 - c.y1 + 1;
  const used = names.slice(0, Math.min(names.length, height));
  const baseHeight = Math.floor(height / used.length);
  let remainder = height % used.length;
  let nextY = c.y1;

  return used.map((name) => {
    const sliceHeight = baseHeight + (remainder > 0 ? 1 : 0);
    remainder -= 1;
    const slice = { ...c, y1: nextY, y2: nextY + sliceHeight - 1 };
    nextY += sliceHeight;
    return { bot: name, cuboid: slice };
  });
}

function splitCuboidByX(cuboid, botNames) {
  const c = normalizeCuboid(cuboid);
  const names = [...new Set((botNames || []).map(safeName).filter(Boolean))];
  if (!names.length) throw new Error('At least one bot is required.');

  const width = c.x2 - c.x1 + 1;
  const used = names.slice(0, Math.min(names.length, width));
  const baseWidth = Math.floor(width / used.length);
  let remainder = width % used.length;
  let nextX = c.x1;

  return used.map((name) => {
    const sliceWidth = baseWidth + (remainder > 0 ? 1 : 0);
    remainder -= 1;
    const slice = { ...c, x1: nextX, x2: nextX + sliceWidth - 1 };
    nextX += sliceWidth;
    return { bot: name, cuboid: slice };
  });
}

function splitCuboidByZ(cuboid, botNames) {
  const c = normalizeCuboid(cuboid);
  const names = [...new Set((botNames || []).map(safeName).filter(Boolean))];
  if (!names.length) throw new Error('At least one bot is required.');

  const depth = c.z2 - c.z1 + 1;
  const used = names.slice(0, Math.min(names.length, depth));
  const baseDepth = Math.floor(depth / used.length);
  let remainder = depth % used.length;
  let nextZ = c.z1;

  return used.map((name) => {
    const sliceDepth = baseDepth + (remainder > 0 ? 1 : 0);
    remainder -= 1;
    const slice = { ...c, z1: nextZ, z2: nextZ + sliceDepth - 1 };
    nextZ += sliceDepth;
    return { bot: name, cuboid: slice };
  });
}

function splitCuboidByLongestHorizontal(cuboid, botNames) {
  const c = normalizeCuboid(cuboid);
  const xDistance = c.x2 - c.x1 + 1;
  const zDistance = c.z2 - c.z1 + 1;
  return xDistance >= zDistance
    ? splitCuboidByX(c, botNames)
    : splitCuboidByZ(c, botNames);
}

const RESOURCE_ALIASES = new Map([
  ['oak', 'minecraft:oak_log'],
  ['oak_log', 'minecraft:oak_log'],
  ['oak_logs', 'minecraft:oak_log'],
  ['oakwood', 'minecraft:oak_log'],
  ['oak_wood', 'minecraft:oak_log'],
  ['jungle', 'minecraft:jungle_log'],
  ['jungle_log', 'minecraft:jungle_log'],
  ['jungle_logs', 'minecraft:jungle_log'],
  ['junglewood', 'minecraft:jungle_log'],
  ['jungle_wood', 'minecraft:jungle_log'],
  ['wood', 'minecraft:oak_log'],
  ['log', 'minecraft:oak_log'],
  ['logs', 'minecraft:oak_log'],
  ['cobble', 'minecraft:cobblestone'],
  ['cobble_stone', 'minecraft:cobblestone'],
  ['cobblestone', 'minecraft:cobblestone'],
  ['torch', 'minecraft:torch'],
  ['torches', 'minecraft:torch'],
  ['minecraft:oak_log', 'minecraft:oak_log'],
  ['minecraft:jungle_log', 'minecraft:jungle_log'],
  ['minecraft:cobblestone', 'minecraft:cobblestone'],
  ['minecraft:torch', 'minecraft:torch']
]);

function resourceBlockNames(resource) {
  const normalized = normalizeResource(resource);
  return normalized === 'minecraft:torch'
    ? ['minecraft:torch', 'minecraft:wall_torch']
    : [normalized];
}

function normalizeResource(value) {
  const key = clean(value).toLowerCase().replace(/\s+/g, '_');
  if (RESOURCE_ALIASES.has(key)) return RESOURCE_ALIASES.get(key);
  if (/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(key)) return key;
  return `minecraft:${key}`;
}

function hashBigInt(seed, salt) {
  let value = BigInt(seed || DEFAULT_WORLD_SEED);
  for (const char of clean(salt)) {
    value ^= BigInt(char.codePointAt(0));
    value *= 1099511628211n;
    value &= (1n << 63n) - 1n;
  }
  return value;
}

function seededOffset(seed, salt, radius) {
  const value = hashBigInt(seed, salt);
  return Number(value % BigInt(radius * 2 + 1)) - radius;
}

function candidateCuboidAround(x, z) {
  return { x1: x - 48, y1: 50, z1: z - 48, x2: x + 48, y2: 120, z2: z + 48 };
}

function planResourceTarget({ resource, amount, seed = DEFAULT_WORLD_SEED, noZones = [], minDistance = 1800 }) {
  const item = normalizeResource(resource);
  const count = asInteger(amount, 'amount');
  if (count < 1) throw new Error('amount must be at least 1.');
  if (item !== 'minecraft:oak_log') throw new Error(`Resource planner only supports minecraft:oak_log right now, got ${item}.`);

  for (let attempt = 0; attempt < 128; attempt += 1) {
    const ring = Math.floor(attempt / 8);
    const radius = minDistance + ring * 512;
    const side = attempt % 8;
    const jitterX = seededOffset(seed, `oak:${attempt}:x`, 192);
    const jitterZ = seededOffset(seed, `oak:${attempt}:z`, 192);
    const positions = [
      [radius, jitterZ],
      [-radius, jitterZ],
      [jitterX, radius],
      [jitterX, -radius],
      [radius, radius],
      [-radius, radius],
      [radius, -radius],
      [-radius, -radius]
    ];
    const [x, z] = positions[side];
    const cuboid = candidateCuboidAround(x, z);
    if (!(noZones || []).some((zone) => !zone.disabled && cuboidsIntersect(cuboid, zone.cuboid || zone))) {
      return {
        resource: item,
        amount: count,
        x,
        z,
        cuboid,
        note: 'Deterministic oak-capable search target; verify biome in-game before large jobs.'
      };
    }
  }

  throw new Error('Could not find a resource target outside configured no-mine zones.');
}

function formatCuboid(cuboid) {
  const c = normalizeCuboid(cuboid);
  return `${c.x1},${c.y1},${c.z1} -> ${c.x2},${c.y2},${c.z2}`;
}

module.exports = {
  DEFAULT_WORLD_SEED,
  clean,
  safeName,
  normalizeCuboid,
  cuboidVolume,
  cuboidsIntersect,
  pointInCuboid,
  assertOutsideNoZones,
  splitCuboidByY,
  splitCuboidByX,
  splitCuboidByZ,
  splitCuboidByLongestHorizontal,
  normalizeResource,
  resourceBlockNames,
  planResourceTarget,
  formatCuboid
};
