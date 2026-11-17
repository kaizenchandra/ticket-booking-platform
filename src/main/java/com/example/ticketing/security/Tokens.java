package com.example.ticketing.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
public class Tokens {
    private final String secret;

    public Tokens(@Value("${ticketing.secret}") String secret) {
        if (secret.length() < 32)
            throw new IllegalArgumentException("Secret must be at least 32 characters");
        this.secret = secret;
    }

    public static String sign(String value, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean equal(String a, String b) {
        return b != null
                && MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public String token(String value) {
        return value + "." + sign(value, secret);
    }

    public boolean valid(String value, String token) {
        return equal(token(value), token);
    }
}
