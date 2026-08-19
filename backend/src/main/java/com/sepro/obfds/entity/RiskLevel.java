package com.sepro.obfds.entity;

/**
 * Risk classification assigned by the fraud detection module (FR-12).
 * Score bands: LOW 0-29, MEDIUM 30-59, HIGH 60 and above.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
