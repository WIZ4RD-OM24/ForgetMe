package dev.forgetme.connector;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The secret stamp on every message between ForgetMe and a connector: HMAC-SHA256 over "timestamp.body".
 * Must match the orchestrator's Crypto.sign exactly; both modules test the same known answer to prove it.
 */
public final class Signatures {

    public static final String TIMESTAMP_HEADER = "X-ForgetMe-Timestamp";
    public static final String SIGNATURE_HEADER = "X-ForgetMe-Signature";
    private static final long MAX_MESSAGE_AGE_SECONDS = 300;

    private Signatures() {}

    public static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    /** False for a wrong signature, and for anything older than 5 minutes. */
    public static boolean verify(String secret, long timestamp, String body, String signature) {
        return signature != null
                && Math.abs(Instant.now().getEpochSecond() - timestamp) <= MAX_MESSAGE_AGE_SECONDS
                && MessageDigest.isEqual(sign(secret, timestamp, body).getBytes(UTF_8), signature.getBytes(UTF_8));
    }
}
