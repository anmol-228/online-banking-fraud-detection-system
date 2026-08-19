import { buildInitialState } from './showcaseData.js';

/**
 * In-memory state for the showcase, persisted to browser storage so a page refresh does not lose
 * what the visitor was doing.
 *
 * Nothing here is sent anywhere. There is no server-side storage in showcase mode, and the demo
 * can be reset at any time from the banner.
 */

const STORAGE_KEY = 'obfds.showcase.state';

let state = null;

function load() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      // A version bump means the seed data changed; start again rather than merging shapes.
      if (parsed && parsed.version === buildInitialState().version) {
        return parsed;
      }
    }
  } catch {
    // Storage can be unavailable (private browsing, blocked cookies). The demo still works,
    // it simply will not survive a refresh.
  }
  return buildInitialState();
}

function persist() {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // Ignore: persistence is a convenience, not a requirement.
  }
}

export function getState() {
  if (state === null) {
    state = load();
  }
  return state;
}

/** Runs a mutation and saves the result. */
export function mutate(fn) {
  const current = getState();
  const result = fn(current);
  persist();
  return result;
}

export function resetShowcase() {
  state = buildInitialState();
  persist();
  return state;
}

export function nextId(kind) {
  const current = getState();
  current.counters[kind] = (current.counters[kind] || 0) + 1;
  return current.counters[kind];
}

/** Reference format matching the backend, for example TXN-20260819-4F2A9C. */
export function reference(prefix) {
  const now = new Date();
  const date =
    `${now.getFullYear()}` +
    `${String(now.getMonth() + 1).padStart(2, '0')}` +
    `${String(now.getDate()).padStart(2, '0')}`;
  let suffix = '';
  for (let i = 0; i < 6; i += 1) {
    suffix += '0123456789ABCDEF'[Math.floor(Math.random() * 16)];
  }
  return `${prefix}-${date}-${suffix}`;
}

export function sixDigitCode() {
  return String(Math.floor(100000 + Math.random() * 900000));
}
