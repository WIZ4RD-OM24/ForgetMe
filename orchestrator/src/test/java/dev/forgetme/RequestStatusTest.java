package dev.forgetme;

import static dev.forgetme.RequestStatus.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RequestStatusTest {

    @Test
    void happyPath() {
        assertEquals(WAITING, RECEIVED.moveTo(WAITING));
        assertEquals(RUNNING, WAITING.moveTo(RUNNING));
        assertEquals(COMPLETED, RUNNING.moveTo(COMPLETED));
    }

    @Test
    void finishedRequestsNeverMove() {
        for (RequestStatus end : List.of(COMPLETED, CANCELLED, REJECTED)) {
            for (RequestStatus to : RequestStatus.values()) {
                assertThrows(IllegalTransition.class, () -> end.moveTo(to));
            }
        }
    }

    @Test
    void noSkippingVerificationAndNoCancellingMidDeletion() {
        assertThrows(IllegalTransition.class, () -> RECEIVED.moveTo(RUNNING));
        assertThrows(IllegalTransition.class, () -> RUNNING.moveTo(CANCELLED));
    }
}
