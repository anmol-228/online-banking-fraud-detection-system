package com.sepro.obfds.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A role that can be granted to an {@link ApplicationUser} (FR-04, FR-19).
 *
 * <p>Roles are stored as rows rather than as a plain enum column so that an administrator can
 * grant and revoke them at run time through the Administration Module.</p>
 */
@Entity
@Table(name = "user_role")
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "name", nullable = false, unique = true, length = 40)
    private RoleName name;

    @Column(name = "description", length = 200)
    private String description;

    protected UserRole() {
        // Required by JPA.
    }

    public UserRole(RoleName name, String description) {
        this.name = name;
        this.description = description;
    }

    /** Spring Security expects authorities to carry the "ROLE_" prefix. */
    public String authority() {
        return "ROLE_" + name.name();
    }

    public Long getId() {
        return id;
    }

    public RoleName getName() {
        return name;
    }

    public void setName(RoleName name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
