import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isNameRequired, normaliseName, singleFlight } from './auth.ts';

test('isNameRequired matches the 422 "Name Required" problem only', () => {
  assert.equal(isNameRequired({ status: 422, title: 'Name Required' }), true);
  assert.equal(isNameRequired({ status: 422, title: 'name required ' }), true);
  assert.equal(isNameRequired({ status: 422, title: 'Validation Failed' }), false);
  assert.equal(isNameRequired({ status: 401, title: 'Name Required' }), false);
});

test('normaliseName trims, collapses whitespace and enforces ≥2 chars', () => {
  assert.equal(normaliseName('  Asha   Devi '), 'Asha Devi');
  assert.equal(normaliseName(' A '), null);
  assert.equal(normaliseName(''), null);
  assert.equal(normaliseName('x'.repeat(200))?.length, 120);
});

test('singleFlight shares one in-flight call between concurrent callers', async () => {
  let calls = 0;
  let release!: (v: string) => void;
  const run = singleFlight(() => {
    calls++;
    return new Promise<string>((r) => (release = r));
  });
  const a = run();
  const b = run();
  assert.equal(a, b);
  release('tok-1');
  assert.deepEqual(await Promise.all([a, b]), ['tok-1', 'tok-1']);
  assert.equal(calls, 1);
});

test('singleFlight starts a fresh call after settling, including after rejection', async () => {
  let calls = 0;
  const run = singleFlight(async () => {
    calls++;
    if (calls === 1) throw new Error('boom');
    return calls;
  });
  await assert.rejects(run(), /boom/);
  assert.equal(await run(), 2);
  assert.equal(await run(), 3);
});
