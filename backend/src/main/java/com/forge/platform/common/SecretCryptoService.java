package com.forge.platform.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authenticated AES-256-GCM secret encryption with key versioning, rotation keyring support,
 * and tenant/purpose Additional Authenticated Data (AAD) binding.
 *
 * Ciphertext format (v1): {@code v1:<keyId>:<base64(12-byte-iv || ciphertext || 16-byte-gcm-tag)>}
 */
@Service
public class SecretCryptoService {

    private static final String FORMAT_VERSION = "v1";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MIN_PAYLOAD_BYTES = GCM_IV_LENGTH + (GCM_TAG_BITS / 8);

    private final String primaryKeyId;
    private final SecretKey primaryKey;
    private final Map<String, SecretKey> keyRing;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public SecretCryptoService(
        @Value("${forge.security.encryption-key}") String rawPrimaryKey,
        @Value("${forge.security.encryption-key-id:k1}") String primaryKeyId,
        @Value("${forge.security.encryption-previous-keys:}") String previousKeysCsv
    ) {
        if (rawPrimaryKey == null || rawPrimaryKey.isBlank()) {
            throw new IllegalArgumentException("forge.security.encryption-key must be configured");
        }
        String normalizedKeyId = (primaryKeyId == null || primaryKeyId.isBlank()) ? "k1" : primaryKeyId.trim();
        if (normalizedKeyId.contains(":")) {
            throw new IllegalArgumentException("Encryption key ID must not contain ':'");
        }
        this.primaryKeyId = normalizedKeyId;
        this.primaryKey = deriveAes256Key(rawPrimaryKey.trim());

        Map<String, SecretKey> ring = new LinkedHashMap<>();
        ring.put(this.primaryKeyId, this.primaryKey);
        if (previousKeysCsv != null && !previousKeysCsv.isBlank()) {
            for (String entry : previousKeysCsv.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) continue;
                int sep = trimmed.indexOf(':');
                if (sep <= 0 || sep == trimmed.length() - 1) {
                    throw new IllegalArgumentException("Invalid encryption-previous-keys entry format; expected keyId:base64Key");
                }
                String kid = trimmed.substring(0, sep).trim();
                String keyMaterial = trimmed.substring(sep + 1).trim();
                ring.putIfAbsent(kid, deriveAes256Key(keyMaterial));
            }
        }
        this.keyRing = Collections.unmodifiableMap(ring);
    }

    public SecretCryptoService(String rawPrimaryKey) {
        this(rawPrimaryKey, "k1", "");
    }

    public String encrypt(String plaintext) {
        return encrypt(plaintext, null);
    }

    public String encrypt(String plaintext, String aad) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, primaryKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && !aad.isEmpty()) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return FORMAT_VERSION + ":" + primaryKeyId + ":" + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    public String decrypt(String encrypted) {
        return decrypt(encrypted, null);
    }

    public String decrypt(String encrypted, String aad) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            if (encrypted.startsWith(FORMAT_VERSION + ":")) {
                String[] parts = encrypted.split(":", 3);
                if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
                    throw new SecretDecryptionException("Malformed versioned ciphertext envelope");
                }
                SecretKey key = keyRing.get(parts[1]);
                if (key == null) {
                    throw new SecretDecryptionException("Unknown encryption key version: " + parts[1]);
                }
                return decryptRawBase64(parts[2], key, aad);
            }
            // Legacy unversioned Base64 format (pre-v1): try decryption without AAD across keyring
            return decryptRawBase64(encrypted, primaryKey, null);
        } catch (SecretDecryptionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SecretDecryptionException("Secret decryption failed (corrupted payload, wrong key, or AAD mismatch)", ex);
        }
    }

    public String reEncrypt(String encrypted, String aad) {
        String plaintext = decrypt(encrypted, aad);
        return encrypt(plaintext, aad);
    }

    public boolean needsRotation(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return false;
        }
        return !encrypted.startsWith(FORMAT_VERSION + ":" + primaryKeyId + ":");
    }

    public String getPrimaryKeyId() {
        return primaryKeyId;
    }

    public String generateSecureToken(int byteLength) {
        int safeLen = Math.max(16, byteLength);
        byte[] bytes = new byte[safeLen];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String sha256Hex(String input) {
        return HexFormat.of().formatHex(sha256Bytes(input == null ? "" : input));
    }

    public String hmacSha256Hex(String payload, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("HMAC secret must not be blank");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    public boolean verifyGithubSignature(String payload, String secret, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=") || secret == null || secret.isBlank()) {
            return false;
        }
        String expected = "sha256=" + hmacSha256Hex(payload, secret);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            signatureHeader.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String maskSecret(String rawSecret) {
        if (rawSecret == null || rawSecret.length() <= 8) {
            return "****";
        }
        String prefix = rawSecret.substring(0, Math.min(6, rawSecret.length() - 4));
        String suffix = rawSecret.substring(rawSecret.length() - 4);
        return prefix + "••••••••" + suffix;
    }

    private String decryptRawBase64(String b64Payload, SecretKey key, String aad) {
        try {
            byte[] decoded = Base64.getDecoder().decode(b64Payload);
            if (decoded.length < MIN_PAYLOAD_BYTES) {
                throw new SecretDecryptionException("Encrypted payload shorter than minimum AES-GCM envelope size");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[decoded.length - GCM_IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(decoded, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && !aad.isEmpty()) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (SecretDecryptionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SecretDecryptionException("AES-GCM authentication failed", ex);
        }
    }

    private SecretKey deriveAes256Key(String rawKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(rawKey);
            if (decoded.length == 32) {
                return new SecretKeySpec(decoded, "AES");
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to SHA-256 derivation for non-base64 dev strings (rejected in prod by ProductionSecurityValidator)
        }
        return new SecretKeySpec(sha256Bytes(rawKey), "AES");
    }

    private byte[] sha256Bytes(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
