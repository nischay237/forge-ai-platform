package com.forge.platform.common;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCryptoServiceTest {

    private static String randomAes256KeyBase64() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void encryptsWithVersionedEnvelopeUniqueIvAndTenantAad() {
        SecretCryptoService crypto = new SecretCryptoService(randomAes256KeyBase64(), "k1", "");
        String aad = "tenant-123:github_token";

        String c1 = crypto.encrypt("ghp_example_secret_token_998877", aad);
        String c2 = crypto.encrypt("ghp_example_secret_token_998877", aad);

        assertThat(c1).startsWith("v1:k1:");
        assertThat(c1).isNotEqualTo(c2); // random IV per encryption
        assertThat(crypto.decrypt(c1, aad)).isEqualTo("ghp_example_secret_token_998877");
        assertThat(crypto.decrypt(c2, aad)).isEqualTo("ghp_example_secret_token_998877");
    }

    @Test
    void rejectsCrossTenantAadMismatchAndTamperedCiphertext() {
        SecretCryptoService crypto = new SecretCryptoService(randomAes256KeyBase64(), "k1", "");
        String encrypted = crypto.encrypt("whsec_super_secret", "tenant-A:webhook_secret");

        // Cross-tenant AAD substitution must fail authentication
        assertThatThrownBy(() -> crypto.decrypt(encrypted, "tenant-B:webhook_secret"))
            .isInstanceOf(SecretDecryptionException.class);

        // Tampering with ciphertext bytes must fail GCM tag verification
        String[] parts = encrypted.split(":", 3);
        byte[] raw = Base64.getDecoder().decode(parts[2]);
        raw[raw.length - 1] ^= 0x01;
        String tampered = parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> crypto.decrypt(tampered, "tenant-A:webhook_secret"))
            .isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void rejectsWrongKeyAndTruncatedPayload() {
        SecretCryptoService serviceA = new SecretCryptoService(randomAes256KeyBase64(), "k1", "");
        SecretCryptoService serviceB = new SecretCryptoService(randomAes256KeyBase64(), "k1", "");

        String encrypted = serviceA.encrypt("secret-value", "tenant-1:purpose");
        assertThatThrownBy(() -> serviceB.decrypt(encrypted, "tenant-1:purpose"))
            .isInstanceOf(SecretDecryptionException.class);

        assertThatThrownBy(() -> serviceA.decrypt("v1:k1:" + Base64.getEncoder().encodeToString(new byte[5]), "tenant-1:purpose"))
            .isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> serviceA.decrypt("v1:unknownKey:AAAA", "tenant-1:purpose"))
            .isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void supportsKeyRotationAcrossPreviousKeyring() {
        String keyV1 = randomAes256KeyBase64();
        String keyV2 = randomAes256KeyBase64();

        SecretCryptoService oldService = new SecretCryptoService(keyV1, "k1", "");
        String oldCiphertext = oldService.encrypt("ghp_rotated_token", "tenant-1:github_token");
        assertThat(oldCiphertext).startsWith("v1:k1:");

        SecretCryptoService rotatedService = new SecretCryptoService(keyV2, "k2", "k1:" + keyV1);
        assertThat(rotatedService.needsRotation(oldCiphertext)).isTrue();
        assertThat(rotatedService.decrypt(oldCiphertext, "tenant-1:github_token")).isEqualTo("ghp_rotated_token");

        String upgraded = rotatedService.reEncrypt(oldCiphertext, "tenant-1:github_token");
        assertThat(upgraded).startsWith("v1:k2:");
        assertThat(rotatedService.needsRotation(upgraded)).isFalse();
        assertThat(rotatedService.decrypt(upgraded, "tenant-1:github_token")).isEqualTo("ghp_rotated_token");
    }
}
