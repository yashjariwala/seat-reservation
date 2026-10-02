package app.seats;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Stateless HMAC tokens: base64url(userId) + "." + base64url(HMAC-SHA256(secret, userId)).
 * The user id is only ever read from a verified token, never from a request body.
 * ponytail: no expiry/refresh — swap for a real IdP-issued JWT in production.
 */
@Component
public class Auth {
    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] secret;
    private final byte[] adminKey;
    // Mac is mutable: each request thread owns its initialized signer.
    private final ThreadLocal<Mac> signers;

    Auth(@Value("${TOKEN_SECRET:dev-token-secret}") String secret,
         @Value("${ADMIN_KEY:dev-admin-key}") String adminKey) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
        this.signers = ThreadLocal.withInitial(() -> {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(this.secret, "HmacSHA256"));
                return mac;
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
    }

    String issue(String userId) {
        if (userId == null || !USER_ID.matcher(userId).matches())
            throw ApiError.badRequest("user_id must match [A-Za-z0-9_-]{1,64}");
        return B64.encodeToString(userId.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(sign(userId));
    }

    /** Returns the verified user id from an "Authorization: Bearer ..." header, or throws 401. */
    String userId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) throw ApiError.unauthorized();
        String[] parts = authHeader.substring(7).trim().split("\\.", -1);
        if (parts.length != 2) throw ApiError.unauthorized();
        try {
            String userId = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            byte[] sig = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(sig, sign(userId))) throw ApiError.unauthorized();
            org.slf4j.MDC.put("user_id", userId);
            return userId;
        } catch (IllegalArgumentException e) {
            throw ApiError.unauthorized();
        }
    }

    void requireAdmin(String key) {
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), adminKey))
            throw ApiError.unauthorized();
    }

    private byte[] sign(String userId) {
        // doFinal resets the Mac to its initialized state for the next message.
        return signers.get().doFinal(userId.getBytes(StandardCharsets.UTF_8));
    }
}
