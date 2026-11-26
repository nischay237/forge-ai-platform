package com.forge.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByIdAndTenantId(String id, String tenantId);
    List<User> findByTenantIdOrderByCreatedAtAsc(String tenantId);
    boolean existsByEmailIgnoreCase(String email);
}
