/**
 * Retry-safe transfer keys.
 *
 * A transfer carries an idempotency key. The backend uses it to recognise "this is the same
 * request again" and answer with the original transfer instead of moving money twice. That only
 * helps if the browser sends the SAME key when it retries - so the rule for when to keep a key
 * and when to replace it is the whole point of this file.
 *
 *   Ambiguous outcome  -> KEEP the key. The request may or may not have reached the bank:
 *                         no response at all (timeout, network drop), a gateway error (502/503/
 *                         504) or any 5xx, "too many requests" (429), a request timeout (408),
 *                         or a "please try again" lock conflict (409 CONCURRENT_UPDATE).
 *                         Sending it again with the same key is safe in every one of those cases.
 *   Definitive answer  -> REPLACE the key. The bank looked at the request and said no (400
 *                         validation, 404, 422 business rule, 409 key already used for something
 *                         else). The customer will correct the form and submit what is, in effect,
 *                         a new request - which must not be mistaken for the old one.
 *
 * A new key is NOT "a duplicate": two intentional identical payments, each with its own key, are
 * two payments. The key identifies one attempt at one request, nothing more.
 *
 * This module is plain JavaScript with no browser dependency so it can be tested with Node
 * (see frontend/scripts/test_key_policy.mjs).
 */

/** Stable description of what a key is for, so a key is never reused for a different request. */
export function fingerprint({ sourceAccountNumber, destinationAccountNumber, amount }) {
  const normalisedAmount = Number.isFinite(Number(amount)) ? Number(amount).toFixed(2) : String(amount);
  return `${String(sourceAccountNumber || '').trim()}|${String(destinationAccountNumber || '').trim()}|${normalisedAmount}`;
}

/**
 * Classifies a failed transfer request.
 *
 * @returns {'ambiguous' | 'definitive'}
 */
export function classifyTransferFailure(error) {
  const status = error?.response?.status;
  if (status === undefined || status === null) {
    return 'ambiguous'; // no response: timeout, dropped connection, CORS/network failure
  }
  if (status >= 500 || status === 408 || status === 425 || status === 429) {
    return 'ambiguous';
  }
  if (status === 409 && error?.response?.data?.code === 'CONCURRENT_UPDATE') {
    return 'ambiguous'; // the bank changed nothing and asked us to try again
  }
  return 'definitive';
}

/**
 * Decides which key the next attempt of the form should use after a failure.
 *
 * @param {string} currentKey the key used by the attempt that just failed
 * @param {unknown} error the axios error
 * @param {() => string} makeKey produces a fresh key
 */
export function keyAfterFailure(currentKey, error, makeKey) {
  return classifyTransferFailure(error) === 'ambiguous' ? currentKey : makeKey();
}

/** A user-facing explanation for an ambiguous failure, or null when the answer was definitive. */
export function ambiguousFailureMessage(error) {
  if (classifyTransferFailure(error) !== 'ambiguous') {
    return null;
  }
  return (
    'We could not confirm whether your transfer was sent. Check your transaction history first. ' +
    'If it is not there, you can confirm again - the same request will not be sent twice.'
  );
}

const STORAGE_PREFIX = 'obfds.transferKey.';

/**
 * Remembers a key for a request across a page reload, so a customer who refreshes after a timeout
 * and submits the same transfer again still retries the same request. Storage failures are
 * ignored: without storage the key simply lives in memory.
 *
 * @param {Storage | undefined} storage defaults to sessionStorage when it exists
 */
export function createKeyStore(storage = globalThis.sessionStorage) {
  function read(prints) {
    try {
      return storage?.getItem(STORAGE_PREFIX + prints) || null;
    } catch {
      return null;
    }
  }
  return {
    /** The key already issued for this request, or a fresh one that is remembered. */
    keyFor(payload, makeKey) {
      const prints = fingerprint(payload);
      const existing = read(prints);
      if (existing) {
        return existing;
      }
      const created = makeKey();
      try {
        storage?.setItem(STORAGE_PREFIX + prints, created);
      } catch {
        // storage unavailable: fall back to the in-memory key
      }
      return created;
    },
    /** Forget the key once the request has a definitive outcome. */
    forget(payload) {
      try {
        storage?.removeItem(STORAGE_PREFIX + fingerprint(payload));
      } catch {
        // nothing to do
      }
    },
  };
}
