/**
 * The fraud risk rules, mirrored for the frontend showcase.
 *
 * These points, thresholds and bands are identical to the backend `FraudRiskService`, so a
 * transfer scored in the public demo produces the same result the full-stack application would
 * produce. Keeping the numbers in one obvious block makes that correspondence easy to check.
 *
 * The engine is deterministic and rule-based. It uses no machine learning.
 *
 * Eight scored rules, evaluated by six checks: two of the checks pick between a pair of
 * mutually exclusive rules (VERY_HIGH_AMOUNT or HIGH_AMOUNT; UNREGISTERED_PAYEE or
 * NEW_BENEFICIARY) rather than raising one unconditionally. Maximum reachable score 140.
 */

const POINTS = {
  VERY_HIGH_AMOUNT: 50,
  HIGH_AMOUNT: 35,
  NEW_BENEFICIARY: 25,
  UNREGISTERED_PAYEE: 20,
  RAPID_TRANSFERS: 20,
  REPEATED_FAILED_VERIFICATION: 20,
  BALANCE_DRAIN: 15,
  UNUSUAL_HOUR: 10,
};

export const FRAUD_CONFIG = {
  highAmount: 50000,
  veryHighAmount: 100000,
  newBeneficiaryHours: 24,
  velocityCount: 3,
  velocityMinutes: 10,
  balanceFraction: 0.8,
  oddHourStart: 0,
  oddHourEnd: 5,
  mediumScoreThreshold: 30,
  highScoreThreshold: 60,
};

export function classify(score) {
  if (score >= FRAUD_CONFIG.highScoreThreshold) return 'HIGH';
  if (score >= FRAUD_CONFIG.mediumScoreThreshold) return 'MEDIUM';
  return 'LOW';
}

/**
 * Assesses one proposed transfer.
 *
 * @param account         the source account
 * @param beneficiary     the saved payee, or null when the destination was typed in
 * @param amount          the transfer amount
 * @param recentTransfers transfers already made by this customer (used by the velocity rule)
 * @param failedVerifications count of failed verifications in the last 24 hours
 * @param now             assessment time, passed in so results are reproducible
 */
export function evaluateRisk({
  account,
  beneficiary,
  amount,
  recentTransfers,
  failedVerifications,
  now,
}) {
  const factors = [];
  const value = Number(amount);
  const at = now instanceof Date ? now : new Date(now);

  // Amount check — raises VERY_HIGH_AMOUNT or HIGH_AMOUNT, never both.
  if (value >= FRAUD_CONFIG.veryHighAmount) {
    factors.push({
      code: 'VERY_HIGH_AMOUNT',
      description: `Transfer amount is at or above the very high limit of ${FRAUD_CONFIG.veryHighAmount}`,
      points: POINTS.VERY_HIGH_AMOUNT,
    });
  } else if (value >= FRAUD_CONFIG.highAmount) {
    factors.push({
      code: 'HIGH_AMOUNT',
      description: `Transfer amount is at or above the high limit of ${FRAUD_CONFIG.highAmount}`,
      points: POINTS.HIGH_AMOUNT,
    });
  }

  // Beneficiary check — raises UNREGISTERED_PAYEE when the payee was never saved, otherwise
  // NEW_BENEFICIARY when it was saved very recently.
  if (!beneficiary) {
    factors.push({
      code: 'UNREGISTERED_PAYEE',
      description: 'Destination account is not in the saved beneficiary list of the customer',
      points: POINTS.UNREGISTERED_PAYEE,
    });
  } else {
    const ageHours = (at.getTime() - new Date(beneficiary.createdAt).getTime()) / 3600000;
    if (ageHours < FRAUD_CONFIG.newBeneficiaryHours) {
      factors.push({
        code: 'NEW_BENEFICIARY',
        description: `Beneficiary was added within the last ${FRAUD_CONFIG.newBeneficiaryHours} hours`,
        points: POINTS.NEW_BENEFICIARY,
      });
    }
  }

  // Velocity check — raises RAPID_TRANSFERS. The transfer being assessed is not counted
  // against itself, which is why the assessment runs before the transaction is stored.
  const windowStart = at.getTime() - FRAUD_CONFIG.velocityMinutes * 60000;
  const recentCount = recentTransfers.filter(
    (txn) => new Date(txn.createdAt).getTime() >= windowStart
  ).length;
  if (recentCount >= FRAUD_CONFIG.velocityCount) {
    factors.push({
      code: 'RAPID_TRANSFERS',
      description: `Customer has made ${recentCount} transfers in the last ${FRAUD_CONFIG.velocityMinutes} minutes`,
      points: POINTS.RAPID_TRANSFERS,
    });
  }

  // Balance check — raises BALANCE_DRAIN when the transfer would take most of the balance.
  if (account.balance > 0 && value > account.balance * FRAUD_CONFIG.balanceFraction) {
    factors.push({
      code: 'BALANCE_DRAIN',
      description: `Transfer would move more than ${Math.round(
        FRAUD_CONFIG.balanceFraction * 100
      )} percent of the account balance`,
      points: POINTS.BALANCE_DRAIN,
    });
  }

  // Hour check — raises UNUSUAL_HOUR inside the configured unusual-activity window.
  const hour = at.getHours();
  if (hour >= FRAUD_CONFIG.oddHourStart && hour < FRAUD_CONFIG.oddHourEnd) {
    factors.push({
      code: 'UNUSUAL_HOUR',
      description: `Transfer was initiated at ${hour}:00, inside the unusual activity window`,
      points: POINTS.UNUSUAL_HOUR,
    });
  }

  // Verification-history check — raises REPEATED_FAILED_VERIFICATION after recent failures.
  if (failedVerifications >= 2) {
    factors.push({
      code: 'REPEATED_FAILED_VERIFICATION',
      description: `Customer has failed verification ${failedVerifications} times in the last 24 hours`,
      points: POINTS.REPEATED_FAILED_VERIFICATION,
    });
  }

  const score = factors.reduce((total, factor) => total + factor.points, 0);

  return {
    score,
    level: classify(score),
    factors,
    reason: factors.length
      ? factors.map((f) => `${f.description} (+${f.points})`).join('; ')
      : 'No risk indicators were triggered.',
  };
}
