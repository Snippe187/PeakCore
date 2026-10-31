package de.peakverse.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.protocol.CorePacket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PackReceiverTest {
    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);

    private static final class FakeEnv implements PackReceiver.Env {
        long now;
        final Set<String> cache = new HashSet<>();
        final List<String> chat = new ArrayList<>();
        final List<String> activated = new ArrayList<>();
        final List<PackReceiver.DownloadCallback> downloads = new ArrayList<>();
        final List<String> downloadedShas = new ArrayList<>();
        String active;
        String pending;

        public long now() { return now; }
        public boolean isCached(String sha1) { return cache.contains(sha1); }
        public void download(String url, String sha1, long size, PackReceiver.DownloadCallback cb) {
            // wie die echte Umgebung: nach erfolgreichem Laden liegt die Datei im Cache
            downloads.add(error -> {
                if (error == null) cache.add(sha1);
                cb.done(error);
            });
            downloadedShas.add(sha1);
        }
        public void activate(String sha1) { activated.add(sha1); active = sha1; }
        public String activeSha1() { return active; }
        public void chat(String message) { chat.add(message); }
        public String pendingSha1() { return pending; }
        public void setPendingSha1(String sha1) { pending = sha1; }
    }

    private FakeEnv env;
    private PackReceiver receiver;

    @BeforeEach
    void setUp() {
        env = new FakeEnv();
        receiver = new PackReceiver(env);
    }

    private static CorePacket.PackOffer offer(String sha) {
        return new CorePacket.PackOffer("http://localhost:8123/pack/" + sha + ".zip", sha, 1000);
    }

    @Test
    void downloadThenWaitsForNextJoin() {
        receiver.onOffer(offer(A));
        assertEquals(PackReceiver.State.DOWNLOADING, receiver.state());
        env.downloads.get(0).done(null);
        assertEquals(PackReceiver.State.PENDING, receiver.state());
        assertEquals(1, env.chat.size());
        assertTrue(env.activated.isEmpty(), "noch nicht aktiviert");
        receiver.onJoin();
        assertEquals(List.of(A), env.activated);
        assertEquals(PackReceiver.State.IDLE, receiver.state());
    }

    @Test
    void cachedPackIsNotDownloadedAgain() {
        env.cache.add(A);
        receiver.onOffer(offer(A));
        assertTrue(env.downloads.isEmpty());
        assertEquals(PackReceiver.State.PENDING, receiver.state());
    }

    @Test
    void alreadyActivePackIsIgnored() {
        env.active = A;
        receiver.onOffer(offer(A));
        assertEquals(PackReceiver.State.IDLE, receiver.state());
        assertTrue(env.downloads.isEmpty() && env.chat.isEmpty());
    }

    @Test
    void remindsEveryTwoMinutesWhilePending() {
        env.cache.add(A);
        receiver.onOffer(offer(A));
        int base = env.chat.size();
        env.now += PackReceiver.REMINDER_MS - 1;
        receiver.tick();
        assertEquals(base, env.chat.size());
        env.now += 1;
        receiver.tick();
        assertEquals(base + 1, env.chat.size());
        receiver.tick();
        assertEquals(base + 1, env.chat.size(), "nicht zweimal in derselben Minute");
        env.now += PackReceiver.REMINDER_MS;
        receiver.tick();
        assertEquals(base + 2, env.chat.size());
    }

    @Test
    void noRemindersAfterActivation() {
        env.cache.add(A);
        receiver.onOffer(offer(A));
        receiver.onJoin();
        int base = env.chat.size();
        env.now += PackReceiver.REMINDER_MS * 5;
        receiver.tick();
        assertEquals(base, env.chat.size());
    }

    @Test
    void failedDownloadIsReportedAndRetriedOnNextOffer() {
        receiver.onOffer(offer(A));
        env.downloads.get(0).done("SHA1 stimmt nicht");
        assertEquals(PackReceiver.State.FAILED, receiver.state());
        assertTrue(env.chat.get(0).contains("SHA1 stimmt nicht"));
        receiver.onJoin();
        assertTrue(env.activated.isEmpty());
        receiver.onOffer(offer(A));
        assertEquals(2, env.downloads.size());
    }

    @Test
    void packWaitingFromAnEarlierSessionIsActivatedAtTheNextJoin() {
        env.cache.add(A);
        env.pending = A; // wie nach einem Neustart des Spiels: nur auf der Platte vermerkt
        receiver.onJoin();
        assertEquals(List.of(A), env.activated);
        assertEquals(null, env.pending);
    }

    @Test
    void pendingPackIsRememberedAndForgottenAfterActivation() {
        env.cache.add(A);
        receiver.onOffer(offer(A));
        assertEquals(A, env.pending);
        receiver.onJoin();
        assertEquals(null, env.pending);
    }

    @Test
    void rememberedPackThatIsNoLongerCachedIsNotActivated() {
        env.pending = A;
        receiver.onJoin();
        assertTrue(env.activated.isEmpty());
    }

    @Test
    void sameOfferWhileDownloadingStartsNothingNew() {
        receiver.onOffer(offer(A));
        receiver.onOffer(offer(A));
        assertEquals(1, env.downloads.size());
    }

    @Test
    void newerOfferSupersedesAnOlderDownload() {
        receiver.onOffer(offer(A));
        receiver.onOffer(offer(B));
        env.downloads.get(0).done(null); // Antwort des alten Downloads kommt zu spaet
        assertEquals(PackReceiver.State.DOWNLOADING, receiver.state());
        env.downloads.get(1).done(null);
        receiver.onJoin();
        assertEquals(List.of(B), env.activated);
    }
}
