package com.sepro.obfds.evidence;

/**
 * Small helpers for what is allowed to leave the system in an evidence export.
 *
 * <p>Account numbers are masked to their last four digits: enough to recognise an account, not
 * enough to use it. (This class used to also try to scrub free text. That was removed: masking
 * e-mail addresses and long numbers does not make prose anonymous, so free text is simply not
 * exported - see {@link EvidenceExportService}.)</p>
 */
public final class Redactor {

    private Redactor() {
    }

    /** {@code ****1234}: enough to recognise an account, not enough to use it. */
    public static String maskAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return null;
        }
        String trimmed = accountNumber.trim();
        return "****" + (trimmed.length() <= 4 ? trimmed : trimmed.substring(trimmed.length() - 4));
    }
}
