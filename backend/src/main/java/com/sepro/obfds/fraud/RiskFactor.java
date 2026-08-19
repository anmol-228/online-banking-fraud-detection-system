package com.sepro.obfds.fraud;

/**
 * One rule that fired while a transaction was being assessed (FR-11).
 *
 * @param code short stable identifier of the rule, for example HIGH_AMOUNT
 * @param description a sentence a reader can understand without opening the code
 * @param points the contribution this rule made to the risk score
 */
public record RiskFactor(String code, String description, int points) {}
