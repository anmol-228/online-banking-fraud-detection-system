package com.sepro.obfds.service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Produces the human-readable references shown on screens and quoted in the audit trail.
 *
 * <p>A reference such as TXN-20260819-4F2A9C is easier for a customer to read out, and easier to
 * follow across the transaction list, the alert, the case and the audit log, than a database
 * identifier would be.</p>
 */
@Component
public class ReferenceGenerator {

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final char[] ALPHABET = "0123456789ABCDEF".toCharArray();

    private final SecureRandom random = new SecureRandom();

    public String transactionReference() {
        return build("TXN");
    }

    public String alertReference() {
        return build("ALT");
    }

    public String caseReference() {
        return build("CSE");
    }

    public String disputeReference() {
        return build("DSP");
    }

    /** Customer numbers do not carry a date, so they stay short and stable. */
    public String customerNumber() {
        return "CUST" + randomDigits(8);
    }

    /** Account numbers are fictional and are only ever used inside this simulation. */
    public String accountNumber() {
        return "9" + randomDigits(11);
    }

    /** A six digit additional verification code (FR-14). */
    public String verificationCode() {
        return randomDigits(6);
    }

    private String build(String prefix) {
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return prefix + "-" + LocalDate.now().format(DATE_PART) + "-" + suffix;
    }

    private String randomDigits(int length) {
        StringBuilder digits = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            digits.append(random.nextInt(10));
        }
        return digits.toString();
    }
}
