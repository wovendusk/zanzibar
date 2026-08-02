package dev.zanzibar.consistency;

import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.RepeatedTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 concurrent stress test.
 * Multiple threads write, delete, and check simultaneously.
 * Asserts that no interleaving ever produces a stale grant.
 */
class ConcurrentConsistencyTest {

    @RepeatedTest(3)
    void noStaleGrantUnderConcurrency() throws Exception {
        var store = new InMemoryTupleStore();
        var docConfig = NamespaceConfig.builder("doc")
                .relation("viewer", RewriteRule.thisRelation())
                .build();
        var engine = new CheckEngine(store, Map.of("doc", docConfig));

        int numDocs = 5;
        int numUsers = 5;
        int opsPerThread = 200;
        AtomicBoolean violationFound = new AtomicBoolean(false);
        AtomicReference<String> violationDetail = new AtomicReference<>("");

        List<ObjectRef> docs = new java.util.ArrayList<>();
        List<SubjectRef> users = new java.util.ArrayList<>();
        for (int i = 0; i < numDocs; i++) docs.add(new ObjectRef("doc", "d" + i));
        for (int i = 0; i < numUsers; i++) users.add(SubjectRef.user("user", "u" + i));

        record OpLog(ObjectRef doc, SubjectRef user, boolean isWrite, Zookie zookie) {}
        CopyOnWriteArrayList<OpLog> log = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(1);

        List<Future<?>> futures = new java.util.ArrayList<>();

        // Writer threads
        for (int w = 0; w < 4; w++) {
            futures.add(pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException e) { return; }
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                    ObjectRef doc = docs.get(rng.nextInt(numDocs));
                    SubjectRef user = users.get(rng.nextInt(numUsers));
                    boolean write = rng.nextBoolean();
                    Zookie z = write
                            ? store.write(doc, "viewer", user)
                            : store.delete(doc, "viewer", user);
                    log.add(new OpLog(doc, user, write, z));
                }
            }));
        }

        // Reader threads — use zookies from completed ops
        for (int r = 0; r < 4; r++) {
            futures.add(pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException e) { return; }
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                    int logSize = log.size();
                    if (logSize == 0) continue;

                    // Pick a committed zookie from the log — guarantees the
                    // underlying write is fully flushed to the store.
                    OpLog entry = log.get(rng.nextInt(logSize));
                    Zookie zookie = entry.zookie();

                    ObjectRef doc = docs.get(rng.nextInt(numDocs));
                    SubjectRef user = users.get(rng.nextInt(numUsers));

                    boolean engineResult = engine.check(doc, "viewer", user, zookie);
                    boolean expected = store.exists(doc, "viewer", user, zookie.revision());

                    if (engineResult != expected) {
                        violationFound.set(true);
                        violationDetail.set("Mismatch at zookie " + zookie
                                + " for " + doc + "#viewer@" + user
                                + ": engine=" + engineResult + " store=" + expected);
                    }
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertFalse(violationFound.get(), violationDetail.get());
    }
}
