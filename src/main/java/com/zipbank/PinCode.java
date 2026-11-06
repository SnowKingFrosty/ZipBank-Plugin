package com.zipbank;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PinCode {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 120_000;

    private PinCode() { }

    static boolean valid(String pin) {
        return pin.matches("[0-9]{4}");
    }

    static String hash(String pin) {
        if (!valid(pin)) {
            throw new IllegalArgumentException("PIN must be exactly four digits.");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derive(pin, salt));
    }

    static boolean matches(String pin, String hash) {
        if (!valid(pin) || hash == null) {
            return false;
        }
        try {
            String[] parts = hash.split(":", -1);
            if (parts.length != 2) {
                return false;
            }
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[1]), derive(pin, Base64.getDecoder().decode(parts[0])));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] derive(String pin, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PIN hashing is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }
}