package com.example.ticketing;

import com.example.ticketing.security.Tokens;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenTest {
    @Test
    void rejectsForgedAndCrossResourceTokens() {
        var t = new Tokens("a-long-secret-at-least-thirty-two-characters");
        assertTrue(t.valid("one", t.token("one")));
        assertFalse(t.valid("two", t.token("one")));
        assertFalse(t.valid("one", t.token("one") + "x"));
        assertFalse(t.valid("one", null));
    }
}
