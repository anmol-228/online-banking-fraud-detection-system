# Fraud Risk Engine

A transparent, deterministic, rule-based risk-scoring engine.

> **This is not machine learning.** It does not learn from past decisions, it has no model to
> train, and it is not presented as predictive AI. It is a rule engine, and its transparency is the
> point.

---

## 1. Why rules rather than a model

Three reasons, in order of importance:

1. **Explainability.** When a transfer is held, the customer sees exactly why, the analyst sees
   exactly why, and the audit entry records exactly why — in the same words. A scored model would
   give a number without a sentence.
2. **Determinism.** The same transfer against the same history always produces the same score. That
   is what makes the behaviour testable: a unit test can assert the score is 60, not "roughly high".
3. **No training data.** This is a simulated bank. There is no labelled history of real fraud to
   learn from, and inventing one would make any model meaningless.

---

## 2. The rules

Implemented in `FraudRiskService` (backend) and mirrored in `showcaseFraudRules.js` (browser demo),
with identical points and thresholds.

| Rule | Fires when | Points | Why it is a signal |
|---|---|---|---|
| `VERY_HIGH_AMOUNT` | amount ≥ 100,000 | 50 | Large amounts carry the largest loss |
| `HIGH_AMOUNT` | amount ≥ 50,000 (below the very-high limit) | 35 | Unusually large for an ordinary customer |
| `NEW_BENEFICIARY` | payee added within the last 24 hours | 25 | Adding a payee then immediately sending money is a common pattern |
| `UNREGISTERED_PAYEE` | destination is not in the customer's saved payee list | 20 | No established relationship with the destination |
| `RAPID_TRANSFERS` | ≥ 3 transfers by this customer in the last 10 minutes | 20 | Rapid sequences suggest automation rather than a person |
| `REPEATED_FAILED_VERIFICATION` | ≥ 2 failed verifications in the last 24 hours | 20 | Someone is failing challenges they should be able to pass |
| `BALANCE_DRAIN` | amount exceeds 80% of the balance | 15 | Emptying an account is unusual for a routine payment |
| `UNUSUAL_HOUR` | initiated between 00:00 and 05:00 | 10 | Weak alone; meaningful in combination |

Maximum reachable score: **140**.

The amount rules are mutually exclusive — a transfer scores either 50 or 35, never both.

---

## 3. Classification

| Score | Level | Outcome |
|---|---|---|
| 0 – 29 | **LOW** | Approved immediately, money moves |
| 30 – 59 | **MEDIUM** | Held as `PENDING_VERIFICATION`; customer enters a six-digit code |
| 60 and above | **HIGH** | Held as `PENDING`; a fraud analyst decides |

Both thresholds, and every rule threshold above, are configuration properties. Tuning the engine
does not require recompiling:

```yaml
obfds:
  fraud:
    high-amount: 50000
    very-high-amount: 100000
    new-beneficiary-hours: 24
    velocity-count: 3
    velocity-minutes: 10
    balance-fraction: 0.80
    odd-hour-start: 0
    odd-hour-end: 5
    medium-score-threshold: 30
    high-score-threshold: 60
```

That matters because the single most likely change request against a fraud system is "there are too
many false positives", and it should be answerable by editing a value.

---

## 4. Two design decisions worth explaining

### Risk is assessed *before* the transaction is stored

The velocity rule counts a customer's transfers inside a time window. If the transaction row were
saved first, the transfer being assessed would be included in its own count, and every third
transfer in the window would be scored as rapid activity.

This was a real defect. It was not caught by the unit tests, because the rule was tested with a
mocked repository that returned whatever the test told it to; and not by the integration tests,
because each made at most two transfers so the threshold of three was never crossed. It was found
by running the system end to end. Two regression tests now cover it: three rapid transfers must
still score zero, and a fourth must fire the rule.

### The full factor list is kept, not just the total

`RiskEvaluation` carries every `RiskFactor` that fired, with its code, its human-readable
description and its points. The alert, the analyst screen, the customer screen and the audit entry
all render from the same list, so they cannot disagree.

It also makes a whole class of future feature cheap: showing which rule contributed most, or
charting rule frequency, needs no new data capture.

---

## 5. Worked examples

| Scenario | Rules fired | Score | Level | Outcome |
|---|---|---|---|---|
| ₹2,500 to an established payee | none | 0 | LOW | Approved instantly |
| ₹55,000 to an established payee | HIGH_AMOUNT | 35 | MEDIUM | Verification code required |
| ₹60,000 to a payee added minutes ago | HIGH_AMOUNT + NEW_BENEFICIARY | 60 | HIGH | Fraud case opened |
| ₹120,000 to a payee added minutes ago | VERY_HIGH_AMOUNT + NEW_BENEFICIARY | 75 | HIGH | Fraud case opened |
| ₹1,000 to an unsaved account number | UNREGISTERED_PAYEE | 20 | LOW | Approved, but the signal is recorded |
| 4th transfer within 10 minutes | RAPID_TRANSFERS | 20 | LOW | Approved, reason cites rapid activity |

---

## 6. What happens after classification

![Suspicious transaction sequence](diagrams/07_sequence_suspicious_transaction.png)

**MEDIUM** — the transfer is held, a fraud alert is raised, and a six-digit code is generated. Only
a BCrypt hash of the code is stored; it is a short-lived secret granting a financial action, so it
is treated exactly like a password. The code has a 10-minute expiry and 3 attempts. Exhausting the
attempts blocks the transfer **and escalates the alert to a fraud case**, because repeated failure
is itself a signal.

**HIGH** — the transfer is held, an alert is raised and a fraud case is opened automatically. An
analyst assigns the case to themselves, reads the reason, and records a decision with **mandatory
remarks** — a decision without a reason cannot be reviewed later. Approving settles the transfer;
blocking stops it permanently. Either way the customer is notified and the decision is audited with
who decided and why.

No high-risk transfer can bypass this. The router has no path that reaches `APPROVED` for a MEDIUM
or HIGH classification without either a successful verification or an analyst decision.

---

## 7. Honest limitations

- **False positives are possible.** A genuine large transfer to a new payee will be held. This is
  mitigated by making every flag recoverable — the customer verifies, or a human decides — rather
  than by weakening the rules.
- **False negatives are possible.** A fraudulent transfer that looks ordinary will score LOW and
  settle. No detection system, simple or advanced, catches everything. The customer is notified of
  every completed transfer and can raise a dispute.
- **Thresholds are chosen for demonstration**, not derived from real transaction data. They make
  the workflow easy to exercise; they do not reflect the behaviour of any real bank.
- **The rules are independent.** There is no interaction modelling — for example, a large amount to
  an established payee at a normal hour is treated the same as a large amount in isolation, beyond
  simple point addition.

---

## 8. Testing

The engine has 10 dedicated unit tests covering each rule in isolation, the score bands at their
exact boundaries (29/30, 59/60), and determinism — evaluating the same transfer twice must produce
an identical score, level and reason.

Integration tests then cover the routing consequences: MEDIUM produces a held transfer with a
pending verification, HIGH produces a held transfer with an open case, and neither can reach
`APPROVED` without the required step.

See [Testing](testing.md).
