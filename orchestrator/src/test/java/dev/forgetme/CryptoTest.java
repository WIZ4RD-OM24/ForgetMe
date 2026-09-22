package dev.forgetme;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CryptoTest {

    Crypto crypto = new Crypto(Base64.getEncoder().encodeToString(new byte[32]), "test-secret");

    @Test
    void encryptionRoundTripsAndHidesThePlaintext() {
        byte[] a = crypto.encrypt("alice@example.com");
        byte[] b = crypto.encrypt("alice@example.com");
        assertEquals("alice@example.com", crypto.decrypt(a));
        assertFalse(Arrays.equals(a, b), "random IV: same input, different output");
        assertFalse(new String(a, UTF_8).contains("alice"));
    }

    @Test
    void tamperedDataIsRejected() {
        byte[] data = crypto.encrypt("alice@example.com");
        data[data.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(data));
    }

    @Test
    void signaturesCatchTamperingWrongSecretsAndOldMessages() {
        String body = "{\"result\":\"DELETED\"}";
        long now = Instant.now().getEpochSecond();
        String signature = Crypto.sign("secret", now, body);
        assertTrue(Crypto.verify("secret", now, body, signature));
        assertFalse(Crypto.verify("secret", now, "{\"result\":\"RETAINED\"}", signature), "body changed");
        assertFalse(Crypto.verify("other-secret", now, body, signature), "wrong secret");
        long tenMinutesAgo = now - 600;
        assertFalse(Crypto.verify("secret", tenMinutesAgo, body, Crypto.sign("secret", tenMinutesAgo, body)), "too old");
        assertFalse(Crypto.verify("secret", now, body, null));
    }

    @Test
    void auditLinksChangeWhenAnythingChanges() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-22T10:00:00.123456Z");
        byte[] prev = new byte[32];
        byte[] link = crypto.chainHash(prev, id, "TASK_DONE", "orders: DELETED", at);
        assertTrue(Arrays.equals(link, crypto.chainHash(prev, id, "TASK_DONE", "orders: DELETED", at)), "repeatable");
        assertFalse(Arrays.equals(link, crypto.chainHash(prev, id, "TASK_DONE", "orders: RETAINED", at)), "detail");
        assertFalse(Arrays.equals(link, crypto.chainHash(prev, id, "TASK_DONE", "orders: DELETED", at.plusNanos(1000))), "time");
        assertFalse(Arrays.equals(link, crypto.chainHash(new byte[] {1}, id, "TASK_DONE", "orders: DELETED", at)), "previous link");
        assertFalse(Arrays.equals(crypto.chainHash(prev, id, "AB", "C", at), crypto.chainHash(prev, id, "A", "BC", at)),
                "fields can't bleed into each other");
        assertFalse(Arrays.equals(link, new Crypto(Base64.getEncoder().encodeToString(new byte[32]), "other-key")
                .chainHash(prev, id, "TASK_DONE", "orders: DELETED", at)), "without the key you can't rebuild the chain");
    }

    @Test
    void codeOnlyMatchesItsOwnRequest() {
        UUID id = UUID.randomUUID();
        byte[] hash = crypto.codeHash(id, "123456");
        assertTrue(crypto.codeMatches(hash, id, "123456"));
        assertFalse(crypto.codeMatches(hash, id, "654321"));
        assertFalse(crypto.codeMatches(hash, UUID.randomUUID(), "123456"));
        assertFalse(crypto.codeMatches(null, id, "123456"));
    }
}
