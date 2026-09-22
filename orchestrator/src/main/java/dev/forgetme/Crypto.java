package dev.forgetme;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Crypto {

    public static final String TIMESTAMP_HEADER = "X-ForgetMe-Timestamp";
    public static final String SIGNATURE_HEADER = "X-ForgetMe-Signature";
    private static final long MAX_MESSAGE_AGE_SECONDS = 300;
    private static final int IV_BYTES = 12;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec aesKey;
    private final byte[] codeKey;

    public Crypto(@Value("${forgetme.encryption-key}") String encryptionKey,
                  @Value("${forgetme.code-secret}") String codeSecret) {
        byte[] key = Base64.getDecoder().decode(encryptionKey);
        if (key.length != 32) throw new IllegalArgumentException("forgetme.encryption-key must be 32 bytes, base64-encoded");
        aesKey = new SecretKeySpec(key, "AES");
        codeKey = codeSecret.getBytes(UTF_8);
    }

    /** AES-256-GCM. Output is the random IV followed by ciphertext + auth tag. */
    public byte[] encrypt(String plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(UTF_8));
            return ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, data, 0, IV_BYTES));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed: wrong key or tampered data", e);
        }
    }

    public String newCode() {
        return "%06d".formatted(random.nextInt(1_000_000));
    }

    /** A connector's shared secret: 32 random bytes. */
    public String newSecret() {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    /** Keyed hash, bound to the request, so a leaked database can't be brute-forced offline. */
    public byte[] codeHash(UUID requestId, String code) {
        return hmac(codeKey, requestId + ":" + code);
    }

    public boolean codeMatches(byte[] storedHash, UUID requestId, String code) {
        return storedHash != null && MessageDigest.isEqual(storedHash, codeHash(requestId, code));
    }

    /** Signs "timestamp.body" with a shared secret: the same idea as Stripe's and GitHub's webhook signatures. */
    public static String sign(String secret, long timestamp, String body) {
        return "sha256=" + HexFormat.of().formatHex(hmac(secret.getBytes(UTF_8), timestamp + "." + body));
    }

    /** False for a wrong signature, and for anything older than 5 minutes, so a captured message can't be replayed later. */
    public static boolean verify(String secret, long timestamp, String body, String signature) {
        return signature != null
                && Math.abs(Instant.now().getEpochSecond() - timestamp) <= MAX_MESSAGE_AGE_SECONDS
                && MessageDigest.isEqual(sign(secret, timestamp, body).getBytes(UTF_8), signature.getBytes(UTF_8));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Hashing failed", e);
        }
    }
}
