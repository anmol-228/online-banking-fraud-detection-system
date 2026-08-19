import { humanise } from '../utils/format.js';

/**
 * Colour coding used consistently across every screen.
 *
 * Keeping the mapping in one component is what stops APPROVED being green on one page and grey on
 * another.
 */
const TONE_BY_VALUE = {
  // Transaction statuses (FR-22)
  APPROVED: 'success',
  PENDING: 'warning',
  PENDING_VERIFICATION: 'warning',
  BLOCKED: 'danger',
  FAILED: 'danger',

  // Risk levels (FR-12)
  LOW: 'success',
  MEDIUM: 'warning',
  HIGH: 'danger',

  // Alert and case statuses
  OPEN: 'warning',
  UNDER_REVIEW: 'info',
  CLOSED: 'neutral',
  RESOLVED_APPROVED: 'success',
  RESOLVED_BLOCKED: 'danger',

  // Verification and dispute statuses
  VERIFIED: 'success',
  EXPIRED: 'danger',
  RESOLVED: 'success',
  REJECTED: 'danger',

  // Account statuses
  ACTIVE: 'success',
  SUCCESS: 'success',
  FAILURE: 'danger',
};

export default function Badge({ value, label }) {
  const tone = TONE_BY_VALUE[value] || 'neutral';
  return <span className={`badge badge--${tone}`}>{label || humanise(value)}</span>;
}
