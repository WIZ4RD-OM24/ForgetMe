package dev.forgetme;

import java.util.Set;

public enum RequestStatus {
    RECEIVED, WAITING, RUNNING, COMPLETED, NEEDS_ATTENTION, CANCELLED, REJECTED;

    /** The only statuses a request may move to from this one. */
    public Set<RequestStatus> next() {
        return switch (this) {
            case RECEIVED -> Set.of(WAITING, REJECTED, CANCELLED);
            case WAITING -> Set.of(RUNNING, CANCELLED);
            case RUNNING -> Set.of(COMPLETED, NEEDS_ATTENTION);
            case NEEDS_ATTENTION -> Set.of(RUNNING);
            case COMPLETED, CANCELLED, REJECTED -> Set.of();
        };
    }

    public RequestStatus moveTo(RequestStatus to) {
        if (!next().contains(to)) throw new IllegalTransition(this, to);
        return to;
    }

    public static class IllegalTransition extends RuntimeException {
        IllegalTransition(RequestStatus from, RequestStatus to) {
            super("Request is " + from + " and can't move to " + to);
        }
    }
}
