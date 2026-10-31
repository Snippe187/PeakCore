package de.peakverse.core.plugin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WebAuthTest {
    private final AtomicLong now = new AtomicLong(1000);
    private final WebAuth auth = new WebAuth(now::get);
    private final UUID player = UUID.randomUUID();

    @Test
    void tokenWorksOnceAndGivesASessionForThePlayer() {
        String token = auth.issueToken(player, "Neo");
        var s = auth.redeem(token, "1.1.1.1").orElseThrow();
        assertEquals("Neo", s.name());
        assertEquals(player, s.player());
        assertTrue(auth.session(s.id()).isPresent());
        assertTrue(auth.redeem(token, "1.1.1.1").isEmpty(), "zweites Einloesen");
    }

    @Test
    void tokenExpiresAfterFiveMinutes() {
        String token = auth.issueToken(player, "Neo");
        now.addAndGet(WebAuth.TOKEN_TTL_MS);
        assertTrue(auth.redeem(token, "1.1.1.1").isEmpty());
    }

    @Test
    void sessionExpiresAndCanBeRevoked() {
        var s = auth.redeem(auth.issueToken(player, "Neo"), "x").orElseThrow();
        now.addAndGet(WebAuth.SESSION_TTL_MS - 1);
        assertTrue(auth.session(s.id()).isPresent());
        now.addAndGet(1);
        assertTrue(auth.session(s.id()).isEmpty());

        var s2 = auth.redeem(auth.issueToken(player, "Neo"), "x").orElseThrow();
        auth.revokeAll(player);
        assertTrue(auth.session(s2.id()).isEmpty());
        assertEquals(0, auth.sessionCount());
    }

    @Test
    void unknownOrNullIdsAreRejected() {
        assertTrue(auth.session(null).isEmpty());
        assertTrue(auth.session("nix").isEmpty());
        assertTrue(auth.redeem(null, "x").isEmpty());
    }

    @Test
    void tooManyWrongTokensBlockTheAddressForAMinute() {
        for (int i = 0; i < WebAuth.MAX_FAILURES_PER_MINUTE; i++) assertTrue(auth.redeem("falsch" + i, "6.6.6.6").isEmpty());
        String good = auth.issueToken(player, "Neo");
        assertTrue(auth.redeem(good, "6.6.6.6").isEmpty(), "gesperrt, auch mit richtigem Schluessel");
        assertTrue(auth.redeem(good, "7.7.7.7").isPresent(), "andere Adresse nicht betroffen");
        String again = auth.issueToken(player, "Neo");
        now.addAndGet(60_000);
        assertFalse(auth.redeem(again, "6.6.6.6").isEmpty(), "nach einer Minute wieder frei");
    }

    @Test
    void tokensAreLongAndUnique() {
        String a = auth.issueToken(player, "Neo"), b = auth.issueToken(player, "Neo");
        assertTrue(a.length() >= 40);
        assertFalse(a.equals(b));
    }
}
