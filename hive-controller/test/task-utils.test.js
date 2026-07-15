const test = require('node:test');
const assert = require('node:assert/strict');
const {
  normalizeCuboid,
  cuboidsIntersect,
  assertOutsideNoZones,
  splitCuboidByY,
  splitCuboidByLongestHorizontal,
  normalizeResource,
  resourceBlockNames,
  planResourceTarget
} = require('../src/task-utils');

test('normalizes cuboid corners', () => {
  assert.deepEqual(normalizeCuboid({ x1: 10, y1: 80, z1: -5, x2: 1, y2: 60, z2: -9 }), {
    x1: 1,
    y1: 60,
    z1: -9,
    x2: 10,
    y2: 80,
    z2: -5
  });
});

test('detects cuboid intersection', () => {
  assert.equal(
    cuboidsIntersect(
      { x1: 0, y1: 0, z1: 0, x2: 10, y2: 10, z2: 10 },
      { x1: 10, y1: 10, z1: 10, x2: 20, y2: 20, z2: 20 }
    ),
    true
  );
  assert.equal(
    cuboidsIntersect(
      { x1: 0, y1: 0, z1: 0, x2: 10, y2: 10, z2: 10 },
      { x1: 11, y1: 0, z1: 0, x2: 20, y2: 10, z2: 10 }
    ),
    false
  );
});

test('rejects targets inside active no-mine zones', () => {
  const zones = [{ name: 'base', cuboid: { x1: -5, y1: 0, z1: -5, x2: 5, y2: 255, z2: 5 } }];
  assert.throws(
    () => assertOutsideNoZones({ x1: 0, y1: 60, z1: 0, x2: 10, y2: 80, z2: 10 }, zones),
    /base/
  );
  assert.doesNotThrow(() => assertOutsideNoZones({ x1: 20, y1: 60, z1: 20, x2: 30, y2: 80, z2: 30 }, zones));
});

test('splits cuboid by Y layer for two bots', () => {
  const slices = splitCuboidByY({ x1: 0, y1: 0, z1: 0, x2: 5, y2: 9, z2: 5 }, ['alpha', 'beta']);
  assert.deepEqual(slices, [
    { bot: 'alpha', cuboid: { x1: 0, y1: 0, z1: 0, x2: 5, y2: 4, z2: 5 } },
    { bot: 'beta', cuboid: { x1: 0, y1: 5, z1: 0, x2: 5, y2: 9, z2: 5 } }
  ]);
});

test('splits colony work along the longest horizontal axis without overlap', () => {
  const slices = splitCuboidByLongestHorizontal(
    { x1: -369, y1: 63, z1: 182, x2: -184, y2: 100, z2: 453 },
    ['kira', 'azure']
  );
  assert.equal(slices[0].cuboid.z2 + 1, slices[1].cuboid.z1);
  assert.equal(cuboidsIntersect(slices[0].cuboid, slices[1].cuboid), false);
  assert.equal(slices[0].cuboid.y1, 63);
  assert.equal(slices[1].cuboid.y2, 100);
});

test('normalizes oak resource aliases', () => {
  assert.equal(normalizeResource('oak wood'), 'minecraft:oak_log');
  assert.equal(normalizeResource('minecraft:oak_log'), 'minecraft:oak_log');
});

test('normalizes cobblestone and torch aliases', () => {
  assert.equal(normalizeResource('cobble stone'), 'minecraft:cobblestone');
  assert.equal(normalizeResource('cobble_stone'), 'minecraft:cobblestone');
  assert.equal(normalizeResource('torches'), 'minecraft:torch');
  assert.deepEqual(resourceBlockNames('torch'), ['minecraft:torch', 'minecraft:wall_torch']);
});

test('plans oak target outside no-mine zones', () => {
  const target = planResourceTarget({
    resource: 'oak_log',
    amount: 64,
    seed: '4145193711797456157',
    noZones: [
      { name: 'spawn', cuboid: { x1: -2000, y1: 0, z1: -2000, x2: 2000, y2: 255, z2: 2000 } }
    ]
  });
  assert.equal(target.resource, 'minecraft:oak_log');
  assert.equal(target.amount, 64);
  assert.ok(Math.abs(target.x) > 2000 || Math.abs(target.z) > 2000);
});
