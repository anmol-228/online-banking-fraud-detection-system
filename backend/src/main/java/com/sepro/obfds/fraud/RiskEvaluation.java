package com.sepro.obfds.fraud;

import com.sepro.obfds.entity.RiskLevel;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The result of assessing one transaction (FR-12).
 *
 * <p>The list of factors is kept, not just the final number, so that the alert, the analyst
 * screen and the audit log can all explain exactly why a transfer was flagged.</p>
 *
 * @param score total of the points contributed by every rule that fired
 * @param level LOW, MEDIUM or HIGH, derived from the score
 * @param factors the rules that fired, in the order they were evaluated
 */
public record RiskEvaluation(int score, RiskLevel level, List<RiskFactor> factors) {

    /** A single readable sentence summarising why the transaction scored what it did. */
    public String reason() {
        if (factors.isEmpty()) {
            return "No risk indicators were triggered.";
        }
        return factors.stream()
                .map(factor -> factor.description() + " (+" + factor.points() + ")")
                .collect(Collectors.joining("; "));
    }

    public boolean requiresAlert() {
        return level != RiskLevel.LOW;
    }
}
