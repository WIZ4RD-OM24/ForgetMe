package dev.forgetme.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SignaturesTest {

    /** The orchestrator's CryptoTest checks the very same answer, so the two sides can't drift apart. */
    @Test
    void matchesTheOrchestratorsSignatures() {
        assertEquals("sha256=b70095be55cf13244de1343649a07601751b1aceeb554e1def7092774d075842",
                Signatures.sign("demo-only-secret-users-change-me", 1700000000L, "{\"result\":\"DELETED\"}"));
    }
}
