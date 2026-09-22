package dev.forgetme.connector;

/**
 * What a connector did. The note ends up on the certificate and in ForgetMe's permanent audit log,
 * so it must never contain personal data.
 */
public record ErasureResult(Result result, String note) {

    public enum Result { DELETED, ANONYMIZED, RETAINED }

    public static ErasureResult deleted() { return new ErasureResult(Result.DELETED, null); }

    public static ErasureResult deleted(String note) { return new ErasureResult(Result.DELETED, note); }

    public static ErasureResult anonymized(String note) { return new ErasureResult(Result.ANONYMIZED, note); }

    /** Kept on purpose, for example because a law says so. The reason is required. */
    public static ErasureResult retained(String reason) { return new ErasureResult(Result.RETAINED, reason); }
}
