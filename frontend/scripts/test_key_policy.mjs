// Contract tests for the transfer-retry and auth-error policy. Pure Node, no test framework:
//   node scripts/test_key_policy.mjs
// Exit code 0 only if every check passes; prints one line per check and a final count so a CI
// log shows that the checks really ran (a green exit code alone proves nothing).
import {
  ambiguousFailureMessage,
  classifyTransferFailure,
  createKeyStore,
  fingerprint,
  keyAfterFailure,
} from '../src/utils/idempotency.js';
import { isAuthRejection, isTransientFailure } from '../src/auth/sessionErrors.js';

let passed = 0;
let failed = 0;
function check(name, condition) {
  if (condition) {
    passed += 1;
    console.log(`PASS  ${name}`);
  } else {
    failed += 1;
    console.log(`FAIL  ${name}`);
  }
}
const http = (status, data = {}) => ({ response: { status, data } });

// ---- when the transfer key is kept or replaced -------------------------------------------
const counter = (() => { let n = 0; return () => `new-${++n}`; })();

check('timeout / dropped connection (no response) is ambiguous', classifyTransferFailure({ code: 'ECONNABORTED' }) === 'ambiguous');
check('network error is ambiguous', classifyTransferFailure(new Error('Network Error')) === 'ambiguous');
for (const status of [500, 502, 503, 504]) {
  check(`HTTP ${status} is ambiguous`, classifyTransferFailure(http(status)) === 'ambiguous');
}
check('HTTP 429 is ambiguous (retry later, same request)', classifyTransferFailure(http(429)) === 'ambiguous');
check('HTTP 408 is ambiguous', classifyTransferFailure(http(408)) === 'ambiguous');
check('409 CONCURRENT_UPDATE is ambiguous (bank changed nothing)', classifyTransferFailure(http(409, { code: 'CONCURRENT_UPDATE' })) === 'ambiguous');
check('409 IDEMPOTENCY_KEY_REUSED is definitive', classifyTransferFailure(http(409, { code: 'IDEMPOTENCY_KEY_REUSED' })) === 'definitive');
check('400 validation is definitive', classifyTransferFailure(http(400)) === 'definitive');
check('404 is definitive', classifyTransferFailure(http(404)) === 'definitive');
check('422 business rule is definitive', classifyTransferFailure(http(422)) === 'definitive');

check('ambiguous failure keeps the same key', keyAfterFailure('k1', http(503), counter) === 'k1');
check('timeout keeps the same key', keyAfterFailure('k1', { code: 'ECONNABORTED' }, counter) === 'k1');
check('definitive failure issues a new key', keyAfterFailure('k1', http(422), counter) === 'new-1');
check('ambiguous failures explain themselves', typeof ambiguousFailureMessage(http(502)) === 'string');
check('definitive failures need no ambiguity message', ambiguousFailureMessage(http(422)) === null);

// ---- a key belongs to exactly one request -------------------------------------------------
const base = { sourceAccountNumber: '900000000001', destinationAccountNumber: '900000000003', amount: '1500' };
check('amount spelling does not change the fingerprint', fingerprint(base) === fingerprint({ ...base, amount: '1500.00' }));
check('a different amount changes the fingerprint', fingerprint(base) !== fingerprint({ ...base, amount: '1501' }));
check('a different destination changes the fingerprint', fingerprint(base) !== fingerprint({ ...base, destinationAccountNumber: '900000000009' }));

const memory = new Map();
const fakeStorage = {
  getItem: (k) => (memory.has(k) ? memory.get(k) : null),
  setItem: (k, v) => memory.set(k, v),
  removeItem: (k) => memory.delete(k),
};
const store = createKeyStore(fakeStorage);
const first = store.keyFor(base, () => 'stored-1');
check('first request gets a fresh key', first === 'stored-1');
check('the same request after a reload reuses the key', store.keyFor({ ...base, amount: '1500.00' }, () => 'stored-2') === 'stored-1');
check('a different request gets its own key', store.keyFor({ ...base, amount: '99' }, () => 'stored-3') === 'stored-3');
store.forget(base);
check('after a definitive outcome the key is forgotten', store.keyFor(base, () => 'stored-4') === 'stored-4');

const brokenStorage = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }, removeItem() { throw new Error('blocked'); } };
check('blocked storage does not break key creation', createKeyStore(brokenStorage).keyFor(base, () => 'mem-1') === 'mem-1');
check('missing storage does not break key creation', createKeyStore(null).keyFor(base, () => 'mem-2') === 'mem-2');

// ---- signing out only on a real authentication rejection ----------------------------------
check('401 is an authentication rejection', isAuthRejection(http(401)) === true);
check('403 is not a reason to discard the token', isAuthRejection(http(403)) === false);
for (const status of [429, 500, 502, 503, 504]) {
  check(`HTTP ${status} on the profile call is transient (token kept)`, isTransientFailure(http(status)) === true && isAuthRejection(http(status)) === false);
}
check('network error is transient (token kept)', isTransientFailure(new Error('Network Error')) === true);
check('401 is not transient', isTransientFailure(http(401)) === false);

console.log(`\n${passed} checks passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
