/** Formatting helpers shared by every screen, so amounts and dates look the same everywhere. */

const currencyFormatter = new Intl.NumberFormat('en-IN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function formatAmount(value, currency = 'INR') {
  if (value === null || value === undefined || value === '') {
    return '-';
  }
  return `${currency} ${currencyFormatter.format(Number(value))}`;
}

export function formatNumber(value) {
  if (value === null || value === undefined) {
    return '-';
  }
  return new Intl.NumberFormat('en-IN').format(Number(value));
}

export function formatDateTime(value) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '-';
  }
  return date.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Turns SCREAMING_SNAKE_CASE into readable text, for example PENDING_VERIFICATION. */
export function humanise(value) {
  if (!value) {
    return '-';
  }
  return value
    .split('_')
    .map((word) => word.charAt(0) + word.slice(1).toLowerCase())
    .join(' ');
}

/**
 * A key that is unique to one transfer form submission.
 *
 * The backend uses it to recognise a repeated submission, so a double click on the transfer
 * button cannot create two transactions.
 */
export function newIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID();
  }
  return `key-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
