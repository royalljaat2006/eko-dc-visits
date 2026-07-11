import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizePhone, rowsFromSheetObjects } from './importRows.ts';

test('header aliases + phone normalization (Excel mangling)', () => {
  const { rows, errors } = rowsFromSheetObjects([
    { 'CSP Code': 'CSP-ND-1001', 'DC Phone': '+91 98000 00006' },
    { csp_code: 'CSP-ND-1002', mobile: 9800000001 },
  ]);
  assert.equal(errors.length, 0);
  assert.deepEqual(rows, [
    { csp_code: 'CSP-ND-1001', dc_phone: '9800000006' },
    { csp_code: 'CSP-ND-1002', dc_phone: '9800000001' },
  ]);
});

test('bad rows are reported with their sheet row number, never dropped silently', () => {
  const { rows, errors } = rowsFromSheetObjects([
    { csp_code: 'CSP-ND-1001', dc_phone: '12345' },
    { csp_code: '', dc_phone: '' }, // blank padding row — ignored
    { csp_code: 'CSP-ND-1002', dc_phone: '9800000001' },
  ]);
  assert.equal(rows.length, 1);
  assert.equal(errors.length, 1);
  assert.match(errors[0]!, /^Row 2:/);
});

test('normalizePhone strips country codes and formatting', () => {
  assert.equal(normalizePhone('919800000001'), '9800000001');
  assert.equal(normalizePhone('98-0000-0001'), '9800000001');
});
