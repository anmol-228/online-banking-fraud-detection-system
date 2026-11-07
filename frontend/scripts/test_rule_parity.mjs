// Parity check: the browser demo's rule engine must give exactly the results the backend produced
// for the committed rule matrix (docs/generated/fraud-rule-matrix.json, written by the backend's
// FraudRuleSpecificationTest). One source of truth, three consumers: backend, documentation, demo.
//   node scripts/test_rule_parity.mjs
import { readFileSync } from 'node:fs';
import { evaluateRisk } from '../src/services/showcase/showcaseFraudRules.js';

const matrix = JSON.parse(readFileSync(new URL('../../docs/generated/fraud-rule-matrix.json', import.meta.url), 'utf8'));
let passed = 0;
const failures = [];

const [year, month, day] = [2026, 11, 10];
/** The instant at which the clock in Asia/Kolkata (UTC+05:30, no daylight saving) reads HH:mm. */
function istInstant(hhmm) {
  const [h, m] = hhmm.split(':').map(Number);
  return new Date(Date.UTC(year, month - 1, day, h, m) - 330 * 60000);
}

for (const entry of matrix.cases) {
  const { input } = entry;
  const now = istInstant(input.istTime);
  const result = evaluateRisk({
    account: { balance: Number(input.balance) },
    beneficiary: input.payeeAgeSeconds === null ? null : { createdAt: new Date(now.getTime() - input.payeeAgeSeconds * 1000).toISOString() },
    amount: input.amount,
    // earlier transfers, all well inside the 10-minute window
    recentTransfers: Array.from({ length: input.earlierTransfers }, () => ({ createdAt: new Date(now.getTime() - 60000).toISOString() })),
    failedVerifications: input.failedVerifications,
    now,
  });
  const rules = result.factors.map((f) => f.code).sort();
  if (result.score === entry.score && result.level === entry.level && JSON.stringify(rules) === JSON.stringify(entry.rules)) {
    passed += 1;
  } else {
    failures.push(`${entry.id}: demo gave ${result.score} ${result.level} [${rules}] but backend gave ${entry.score} ${entry.level} [${entry.rules}]`);
  }
}

// Boundaries that a count-only matrix cannot express: the velocity window is "strictly after".
const now = istInstant('12:00');
const tenMinutesAgo = new Date(now.getTime() - 10 * 60000).toISOString();
const justInside = new Date(now.getTime() - 10 * 60000 + 1000).toISOString();
const base = { account: { balance: 1e7 }, beneficiary: { createdAt: new Date(now.getTime() - 30 * 86400000).toISOString() }, amount: '2500.00', failedVerifications: 0, now };
const atEdge = evaluateRisk({ ...base, recentTransfers: [1, 2, 3].map(() => ({ createdAt: tenMinutesAgo })) });
const inside = evaluateRisk({ ...base, recentTransfers: [1, 2, 3].map(() => ({ createdAt: justInside })) });
function check(name, condition) {
  if (condition) { passed += 1; } else { failures.push(name); }
}
check('transfers exactly 10 minutes old do not count (strictly after the window start)', atEdge.score === 0);
check('transfers one second inside the window do count', inside.score === 20);
// Floating-point trap: 80 percent of 10000.01 is 8000.008; 8000.01 is over it.
check('balance drain is exact to the paisa (8000.00 of 10000.00 no, 8000.01 yes)',
  evaluateRisk({ ...base, account: { balance: 10000 }, amount: '8000.00', recentTransfers: [] }).score === 0
  && evaluateRisk({ ...base, account: { balance: 10000 }, amount: '8000.01', recentTransfers: [] }).score === 15);
// Time zone: 02:00 in India is unusual no matter where the visitor's browser is.
check('unusual hour is read in Asia/Kolkata', evaluateRisk({ ...base, now: istInstant('02:00'), recentTransfers: [] }).score === 10);

console.log(`${passed} checks passed against ${matrix.cases.length} backend-generated cases + 4 boundary checks`);
if (failures.length > 0) {
  failures.slice(0, 20).forEach((line) => console.log(`FAIL  ${line}`));
  console.log(`${failures.length} failed`);
  process.exit(1);
}
