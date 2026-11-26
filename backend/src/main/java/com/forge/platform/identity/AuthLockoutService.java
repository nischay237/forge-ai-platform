package com.forge.platform.identity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Isolated REQUIRES_NEW persistence for failed login attempts so brute-force lockout counters
 * are never rolled back when AuthService throws an authentication exception.
 */
@Service
public class AuthLockoutService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public AuthLockoutService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeRefreshFamily(String familyId) {
        var familyTokens = refreshTokenRepository.findByFamilyIdAndRevokedAtIsNull(familyId);
        for (RefreshTokenEntity rt : familyTokens) {
            rt.revoke(null);
        }
        refreshTokenRepository.saveAll(familyTokens);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(String userId) {
        return userRepository.findById(userId).map(user -> {
            user.recordFailedLogin(MAX_FAILED_ATTEMPTS, LOCKOUT_DURATION);
            userRepository.save(user);
            return user.isLocked();
        }).orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetFailures(String userId) {
        userRepository.findById(userId).ifPresent(user -> {
            if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
                user.resetFailedLogins();
                userRepository.save(user);
            }
        });
    }
}
