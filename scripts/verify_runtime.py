"""End-to-end runtime verification of the Online Banking & Fraud Detection System.

Exercises the real HTTP API of the running backend and prints a PASS/FAIL line per check.
"""

import json
import re
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
results = []


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            text = response.read().decode()
            return response.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as err:
        text = err.read().decode()
        return err.code, (json.loads(text) if text else None)


def check(label, condition, detail=""):
    results.append((label, bool(condition), detail))
    print(("PASS  " if condition else "FAIL  ") + label + ((" | " + detail) if detail else ""))


def login(username, password):
    status, body = call("POST", "/api/auth/login", body={"username": username, "password": password})
    assert status == 200, f"login failed for {username}: {status} {body}"
    return body["token"]


print("=" * 78)
print("RUNTIME VERIFICATION - Online Banking & Fraud Detection System")
print("=" * 78)

# --- Authentication -------------------------------------------------------
status, body = call("POST", "/api/auth/login",
                    body={"username": "ravi.kumar", "password": "Customer@123"})
check("FR-02 valid login returns a token", status == 200 and body.get("token"),
      f"roles={body.get('roles')}")
customer = body["token"]

status, body = call("POST", "/api/auth/login",
                    body={"username": "ravi.kumar", "password": "WrongPass@1"})
check("FR-02 invalid login rejected", status == 401 and body["code"] == "INVALID_CREDENTIALS",
      f"HTTP {status}")

status, _ = call("GET", "/api/accounts")
check("FR-03 unauthenticated access blocked", status == 401, f"HTTP {status}")

status, _ = call("GET", "/api/admin/users", customer)
check("FR-04 customer blocked from admin endpoint", status == 403, f"HTTP {status}")

# --- Registration ---------------------------------------------------------
status, body = call("POST", "/api/auth/register", body={
    "username": "demo.newuser", "password": "NewUser@12345", "fullName": "Demo New User",
    "email": "demo.newuser@demomail.example", "phone": "9876500123", "address": "1 Demo Street"})
check("FR-01 registration creates a profile", status == 201 and body.get("token"),
      f"customerNumber={body.get('customerNumber')}")

# --- Accounts and balance -------------------------------------------------
status, accounts = call("GET", "/api/accounts", customer)
check("FR-05 account list returned", status == 200 and len(accounts) == 2,
      f"{len(accounts)} accounts")

status, balance = call("GET", "/api/accounts/900000000001/balance", customer)
check("FR-06 balance enquiry", status == 200 and float(balance["balance"]) == 150000.0,
      f"balance={balance['balance']}")

status, _ = call("GET", "/api/accounts/900000000003/balance", customer)
check("NFR-08 cannot read another customer account", status == 404, f"HTTP {status}")

# --- Beneficiaries --------------------------------------------------------
status, beneficiaries = call("GET", "/api/beneficiaries", customer)
check("FR-08 seeded beneficiaries listed", status == 200 and len(beneficiaries) == 2,
      f"{len(beneficiaries)} payees")

# --- Validation -----------------------------------------------------------
status, body = call("POST", "/api/transactions/transfer", customer, {
    "sourceAccountNumber": "900000000001", "destinationAccountNumber": "900000000003",
    "amount": "-100.00"})
check("FR-09 negative amount rejected", status == 400 and body["code"] == "VALIDATION_FAILED")

status, body = call("POST", "/api/transactions/transfer", customer, {
    "sourceAccountNumber": "900000000001", "destinationAccountNumber": "900000000003",
    "amount": "900000.00"})
check("FR-09 insufficient balance rejected", status == 422 and body["code"] == "INSUFFICIENT_BALANCE")

status, body = call("POST", "/api/transactions/transfer", customer, {
    "sourceAccountNumber": "900000000001", "destinationAccountNumber": "900000000001",
    "amount": "100.00"})
check("FR-09 same-account transfer rejected", status == 422 and body["code"] == "SAME_ACCOUNT_TRANSFER")

# --- T1: low risk ---------------------------------------------------------
status, t1 = call("POST", "/api/transactions/transfer", customer, {
    "sourceAccountNumber": "900000000001", "destinationAccountNumber": "900000000003",
    "amount": "2500.00", "description": "Low risk demo transfer", "idempotencyKey": "verify-t1"})
check("FR-07/FR-12 low risk transfer approved",
      status == 201 and t1["status"] == "APPROVED" and t1["riskLevel"] == "LOW",
      f"{t1['reference']} score={t1['riskScore']}")

status, balance = call("GET", "/api/accounts/900000000001/balance", customer)
check("NFR-02 source account debited", float(balance["balance"]) == 147500.0,
      f"balance={balance['balance']}")

meera = login("meera.nair", "Customer@123")
status, meera_balance = call("GET", "/api/accounts/900000000003/balance", meera)
check("NFR-02 destination account credited", float(meera_balance["balance"]) == 122500.0,
      f"balance={meera_balance['balance']}")

status, again = call("POST", "/api/transactions/transfer", customer, {
    "sourceAccountNumber": "900000000001", "destinationAccountNumber": "900000000003",
    "amount": "2500.00", "description": "Low risk demo transfer", "idempotencyKey": "verify-t1"})
check("NFR-09 idempotency key returns the same transfer",
      again["reference"] == t1["reference"], again["reference"])

status, body = call("POST", "/api/transactions/transfer", customer, {
    "sourceAccountNumber": "900000000001", "destinationAccountNumber": "900000000003",
    "amount": "2500.00", "description": "Repeat"})
check("NFR-09 duplicate submission rejected",
      status == 409 and body["code"] == "DUPLICATE_TRANSACTION", f"HTTP {status}")

status, notifications = call("GET", "/api/notifications", customer)
check("FR-16 customer notified of completed transfer",
      any(n["relatedReference"] == t1["reference"] and n["title"] == "Transfer completed"
          for n in notifications), f"{len(notifications)} notifications")

# --- T2: medium risk, additional verification -----------------------------
status, t2 = call("POST", "/api/transactions/transfer", customer, {
    "sourceAccountNumber": "900000000001", "destinationAccountNumber": "900000000003",
    "amount": "60000.00", "description": "Medium risk demo transfer"})
check("FR-12 large transfer classified MEDIUM",
      status == 201 and t2["riskLevel"] == "MEDIUM" and t2["riskScore"] == 35,
      f"{t2['reference']} score={t2['riskScore']}")
check("FR-14 medium risk held for verification",
      t2["status"] == "PENDING_VERIFICATION" and t2["verificationRequired"], t2["status"])

status, balance = call("GET", "/api/accounts/900000000001/balance", customer)
check("NFR-09 held transfer reserves funds without moving them",
      float(balance["balance"]) == 147500.0 and float(balance["reservedAmount"]) == 60000.0,
      f"available={balance['availableBalance']}")

analyst = login("analyst.fraud", "Analyst@123")
status, alerts = call("GET", "/api/alerts", analyst)
check("FR-13 fraud alert raised and visible to analyst",
      status == 200 and any(a["transactionReference"] == t2["reference"] for a in alerts),
      f"{len(alerts)} alerts")

status, notifications = call("GET", "/api/notifications", customer)
code = None
for n in notifications:
    if n["type"] == "VERIFICATION" and n["relatedReference"] == t2["reference"]:
        match = re.search(r"\b(\d{6})\b", n["message"])
        if match:
            code = match.group(1)
check("FR-14 verification code delivered to the customer", code is not None, f"code={code}")

status, body = call("POST", f"/api/transactions/{t2['reference']}/verify", customer, {"code": "000000"})
check("FR-14 wrong verification code rejected",
      status == 422 and body["code"] == "INVALID_VERIFICATION_CODE", body["message"])

# The rejection message alone is not evidence: it is computed in memory and was, at one point,
# reported correctly while the underlying counter was rolled back with the exception that
# reported it. Re-read the stored request so the attempt is proven to have been written down.
status, vstate = call("GET", f"/api/transactions/{t2['reference']}/verification", customer)
check("FR-14 the rejected attempt is actually recorded, not just reported",
      status == 200 and vstate["attempts"] == 1 and vstate["attemptsRemaining"] == 2,
      f"attempts={vstate.get('attempts')} remaining={vstate.get('attemptsRemaining')}")

status, verified = call("POST", f"/api/transactions/{t2['reference']}/verify", customer, {"code": code})
check("FR-15 correct code releases the transfer",
      status == 200 and verified["status"] == "APPROVED", verified["status"])

status, balance = call("GET", "/api/accounts/900000000001/balance", customer)
check("NFR-02 money moves only after verification",
      float(balance["balance"]) == 87500.0 and float(balance["reservedAmount"]) == 0.0,
      f"balance={balance['balance']}")

# --- T3: high risk, fraud case review -------------------------------------
status, _ = call("POST", "/api/beneficiaries", customer, {
    "name": "Unknown Payee", "accountNumber": "400000000777",
    "bankName": "Example Bank", "ifscCode": "EXMP0000456", "nickname": "New payee"})
check("FR-08 new beneficiary added", status == 201, f"HTTP {status}")

status, t3 = call("POST", "/api/transactions/transfer", customer, {
    "sourceAccountNumber": "900000000001", "destinationAccountNumber": "400000000777",
    "amount": "60000.00", "description": "High risk demo transfer"})
check("FR-12 large transfer to a new payee classified HIGH",
      status == 201 and t3["riskLevel"] == "HIGH" and t3["riskScore"] == 60,
      f"{t3['reference']} score={t3['riskScore']} reason={t3['riskReason'][:60]}")
check("FR-15 high risk transfer held for fraud review", t3["status"] == "PENDING", t3["status"])

status, cases = call("GET", "/api/fraud-cases", analyst)
open_case = next((c for c in cases if c["transactionReference"] == t3["reference"]), None)
check("FR-18 fraud case opened automatically", open_case is not None,
      open_case["reference"] if open_case else "none")

status, assigned = call("POST", f"/api/fraud-cases/{open_case['reference']}/assign", analyst)
check("FR-18 analyst can take ownership of the case",
      status == 200 and assigned["status"] == "UNDER_REVIEW", assigned["assignedTo"])

status, decided = call("POST", f"/api/fraud-cases/{open_case['reference']}/decision", analyst,
                       {"decision": "BLOCK", "remarks": "Customer did not recognise this payee."})
check("FR-15 analyst decision blocks the transfer",
      status == 200 and decided["status"] == "RESOLVED_BLOCKED"
      and decided["transactionStatus"] == "BLOCKED", decided["transactionStatus"])

status, balance = call("GET", "/api/accounts/900000000001/balance", customer)
check("FR-22 blocked transfer leaves the balance untouched",
      float(balance["balance"]) == 87500.0 and float(balance["availableBalance"]) == 87500.0,
      f"balance={balance['balance']}")

status, notifications = call("GET", "/api/notifications", customer)
check("FR-16 customer notified that the transfer was blocked",
      any(n["relatedReference"] == t3["reference"] and n["title"] == "Transfer blocked"
          for n in notifications))

# --- History --------------------------------------------------------------
status, history = call("GET", "/api/transactions?page=0&size=20", customer)
check("FR-10 transaction history lists all transfers",
      status == 200 and history["totalElements"] == 3,
      f"{history['totalElements']} transactions")

# --- Disputes -------------------------------------------------------------
status, dispute = call("POST", "/api/disputes", customer, {
    "transactionReference": t1["reference"], "subject": "I did not authorise this transfer",
    "description": "This transfer appeared on my account and I did not make it."})
check("FR-17 customer can raise a complaint",
      status == 201 and dispute["status"] == "OPEN", dispute["reference"])

officer = login("ops.officer", "Officer@123")
status, queue = call("GET", "/api/disputes/queue", officer)
check("FR-17 complaint appears in the operations queue",
      status == 200 and any(d["reference"] == dispute["reference"] for d in queue),
      f"{len(queue)} in queue")

status, resolved = call("POST", f"/api/disputes/{dispute['reference']}/resolve", officer, {
    "status": "RESOLVED", "resolution": "Confirmed as genuine after speaking to the customer."})
check("FR-17 officer resolves the complaint",
      status == 200 and resolved["status"] == "RESOLVED", resolved["handledBy"])

# --- Administration -------------------------------------------------------
admin = login("admin.bank", "Admin@123")
status, users = call("GET", "/api/admin/users", admin)
check("FR-19 administrator lists users", status == 200 and len(users) >= 6, f"{len(users)} users")

officer_user = next(u for u in users if u["username"] == "ops.officer")
status, updated = call("PUT", f"/api/admin/users/{officer_user['id']}/roles", admin,
                       {"roles": ["OPS_OFFICER", "FRAUD_ANALYST"]})
check("FR-19 administrator can grant a role",
      status == 200 and set(updated["roles"]) == {"OPS_OFFICER", "FRAUD_ANALYST"},
      str(updated["roles"]))

status, restored = call("PUT", f"/api/admin/users/{officer_user['id']}/roles", admin,
                        {"roles": ["OPS_OFFICER"]})
check("FR-19 administrator can revoke a role", status == 200 and restored["roles"] == ["OPS_OFFICER"])

# --- Audit ----------------------------------------------------------------
status, audit = call("GET", "/api/audit?size=200", admin)
actions = {entry["action"] for entry in audit["content"]}
expected = {"LOGIN_SUCCESS", "LOGIN_FAILURE", "TRANSFER_INITIATED", "RISK_EVALUATED",
            "TRANSACTION_APPROVED", "TRANSACTION_BLOCKED", "FRAUD_ALERT_RAISED",
            "VERIFICATION_REQUESTED", "VERIFICATION_SUCCESS", "VERIFICATION_FAILED",
            "FRAUD_CASE_OPENED", "FRAUD_CASE_DECIDED", "DISPUTE_SUBMITTED",
            "DISPUTE_RESOLVED", "USER_ROLES_UPDATED", "BENEFICIARY_ADDED", "REGISTER"}
missing = expected - actions
check("FR-20 audit trail records every important event", not missing,
      f"{audit['totalElements']} entries" + (f", missing={missing}" if missing else ""))

# --- Reports --------------------------------------------------------------
status, operational = call("GET", "/api/reports/operational", admin)
check("FR-21 operational report generated",
      status == 200 and operational["totalTransactions"] == 3
      and operational["approvedTransactions"] == 2 and operational["blockedTransactions"] == 1,
      f"approved={operational['approvedTransactions']} blocked={operational['blockedTransactions']}")

status, fraud_report = call("GET", "/api/reports/fraud", admin)
check("FR-21 fraud report generated",
      status == 200 and fraud_report["totalAlerts"] == 2 and fraud_report["casesBlocked"] == 1,
      f"alerts={fraud_report['totalAlerts']} highRisk={fraud_report['highRiskTransactions']}")

# --- Summary --------------------------------------------------------------
passed = sum(1 for _, ok, _ in results if ok)
total = len(results)
print("=" * 78)
print(f"RESULT: {passed}/{total} checks passed")
print("=" * 78)
for label, ok, detail in results:
    if not ok:
        print("FAILED: " + label + " | " + detail)
sys.exit(0 if passed == total else 1)
