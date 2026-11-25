package com.forge.platform.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public User() {}

    public static User create(String tenantId, String email, String fullName, String passwordHash, Role role) {
        User u = new User();
        u.id = UUID.randomUUID().toString();
        u.tenantId = tenantId;
        u.email = email.toLowerCase().trim();
        u.fullName = fullName.trim();
        u.passwordHash = passwordHash;
        u.role = role;
        u.active = true;
        u.createdAt = Instant.now();
        return u;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
