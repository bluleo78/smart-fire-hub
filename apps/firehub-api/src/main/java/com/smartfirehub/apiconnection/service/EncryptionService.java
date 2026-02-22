package com.smartfirehub.apiconnection.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    @Value("${app.encryption.master-key}")
    private String masterKeyBase64;

    private SecretKey secretKey;

    @PostConstruct
    void init() {
        byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
        secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    // Package-private constructor for unit tests
    EncryptionService(String masterKeyBase64) {
        byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    // No-arg constructor for Spring (Spring uses @PostConstruct to set key)
    EncryptionService() {}

    /**
     * Encrypts plainText using AES-256-GCM with a random 12-byte IV.
     *
     * @return Base64-encoded string in the format {@code iv:ciphertext}
     */
    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] cipherBytes = cipher.doFinal(plainText.getBytes());

            String ivBase64 = Base64.getEncoder().encodeToString(iv);
            String cipherBase64 = Base64.getEncoder().encodeToString(cipherBytes);
            return ivBase64 + ":" + cipherBase64;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64-encoded {@code iv:ciphertext} string.
     *
     * @return the original plain text
     */
    public String decrypt(String encryptedText) {
        try {
            String[] parts = encryptedText.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid encrypted format — expected 'iv:ciphertext'");
            }

            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] cipherBytes = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plainBytes = cipher.doFinal(cipherBytes);
            return new String(plainBytes);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /**
     * Returns {@code ****} followed by the last 4 characters of value,
     * or {@code ****} if value has fewer than 4 characters.
     */
    public String maskValue(String value) {
        if (value == null || value.length() < 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }
}
