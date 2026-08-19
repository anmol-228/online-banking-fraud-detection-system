package com.sepro.obfds.entity;

/**
 * Application roles used for authorization (FR-04).
 * Spring Security authorities are these names prefixed with "ROLE_".
 */
public enum RoleName {
    CUSTOMER,
    BANK_ADMIN,
    FRAUD_ANALYST,
    OPS_OFFICER,
    SYSTEM_ADMIN
}
