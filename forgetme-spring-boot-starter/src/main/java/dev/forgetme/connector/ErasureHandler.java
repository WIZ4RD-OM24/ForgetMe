package dev.forgetme.connector;

import java.util.UUID;

/**
 * The one thing a connector has to write: how to delete a person from this system.
 * Define it as a bean and the starter does the rest (endpoint, signature checks, reporting back).
 *
 * <p>It may run more than once for the same person (ForgetMe re-sends jobs it hasn't heard back about), so deleting
 * something that's already gone must simply succeed. Throw to say "failed, try again later".
 */
@FunctionalInterface
public interface ErasureHandler {

    ErasureResult erase(Subject subject) throws Exception;

    /** Who to delete. */
    record Subject(UUID requestId, String email) {}
}
