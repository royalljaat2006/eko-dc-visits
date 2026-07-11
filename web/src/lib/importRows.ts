/**
 * Maps spreadsheet rows (as sheet_to_json objects) into the C2 v0.6.0 bulk
 * import payload. Header aliases are forgiving because real Excel sheets are;
 * every skipped row is reported, never silently dropped (staged-import
 * doctrine). Pure function — unit-tested without a DOM or a real workbook.
 */
export interface ImportRow {
  csp_code: string;
  dc_phone: string;
}

const CSP_KEYS = ['csp_code', 'csp code', 'code', 'csp'];
const PHONE_KEYS = ['dc_phone', 'dc phone', 'phone', 'mobile', 'dc mobile', 'dc'];

function pick(obj: Record<string, unknown>, keys: string[]): string {
  for (const [k, v] of Object.entries(obj)) {
    if (keys.includes(k.trim().toLowerCase())) return String(v ?? '').trim();
  }
  return '';
}

/** Keep the last 10 digits (Excel mangles numbers into 919800… or 9.8e9 forms). */
export function normalizePhone(raw: string): string {
  const digits = raw.replace(/\D/g, '');
  return digits.length > 10 ? digits.slice(-10) : digits;
}

export function rowsFromSheetObjects(objects: Array<Record<string, unknown>>): { rows: ImportRow[]; errors: string[] } {
  const rows: ImportRow[] = [];
  const errors: string[] = [];
  objects.forEach((obj, i) => {
    const csp_code = pick(obj, CSP_KEYS);
    const dc_phone = normalizePhone(pick(obj, PHONE_KEYS));
    if (!csp_code && !dc_phone) return; // fully blank row — Excel padding, ignore
    if (!csp_code || dc_phone.length !== 10) {
      errors.push(`Row ${i + 2}: needs csp_code and a 10-digit dc_phone (got "${csp_code}", "${dc_phone}")`);
      return;
    }
    rows.push({ csp_code, dc_phone });
  });
  if (objects.length === 0) errors.push('Sheet has no data rows (expected headers: csp_code, dc_phone)');
  return { rows, errors };
}
