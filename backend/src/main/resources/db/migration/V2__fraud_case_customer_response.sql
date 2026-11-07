-- =============================================================================
-- V2: customer response to a held high-risk transfer, and the review target time.
--
-- Additive and backward compatible: three NULLable columns on fraud_case, no default needed,
-- no existing row changes, no ENUM is altered. The previous application build keeps running
-- against this schema (it simply never reads these columns), which is what makes a rollback of
-- the code safe without restoring the database.
--
--   customer_response      CONFIRMED_MINE or NOT_MINE; written once, by the transfer's owner.
--                          It is EVIDENCE for the analyst. It never releases money.
--   customer_responded_at  when the response was recorded.
--   review_due_at          the TARGET by which an analyst is expected to review the case. It is
--                          shown to the customer and the analyst; nothing is enforced or
--                          released automatically when it passes.
-- =============================================================================
ALTER TABLE fraud_case
  ADD COLUMN customer_response VARCHAR(16) NULL,
  ADD COLUMN customer_responded_at DATETIME(6) NULL,
  ADD COLUMN review_due_at DATETIME(6) NULL;
