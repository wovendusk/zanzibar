package dev.zanzibar.consistency;

import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The headline test: prove the new enemy problem is solved.
 *
 * Scenario: Alice removes Bob's access, then adds secret content.
 * A check using the content's zookie must see the revocation.
 */
class NewEnemyTest {

    @Test
    void revokeBeforeContentCreation_checkWithContentZookie_denied() {
        var store = new InMemoryTupleStore();
        var docConfig = NamespaceConfig.builder("doc")
                .relation("viewer", RewriteRule.thisRelation())
                .build();
        var engine = new CheckEngine(store, Map.of("doc", docConfig));

        ObjectRef doc = new ObjectRef("doc", "secret");
        SubjectRef bob = SubjectRef.user("user", "bob");

        // Step 1: Bob is granted viewer access
        Zookie z1 = store.write(doc, "viewer", bob);
        assertTrue(engine.check(doc, "viewer", bob, z1),
                "At z1, Bob should have access");

        // Step 2: Alice revokes Bob's access
        Zookie z2 = store.delete(doc, "viewer", bob);

        // Step 3: Alice adds secret content (simulated — the content system
        // records the current revision as the content's zookie)
        Zookie zContent = new Zookie(store.latestRevision());

        // THE CRITICAL CHECK: using the content's zookie, Bob must be denied.
        assertFalse(engine.check(doc, "viewer", bob, zContent),
                "New enemy: check with content zookie must see the revocation");

        // Sanity: at the old zookie, Bob still had access
        assertTrue(engine.check(doc, "viewer", bob, z1),
                "At z1 (before revocation), Bob should still have access");
    }

    @Test
    void revokeAndReGrant_zookieRespectsRevision() {
        var store = new InMemoryTupleStore();
        var engine = new CheckEngine(store, Map.of());

        ObjectRef doc = new ObjectRef("doc", "memo");
        SubjectRef eve = SubjectRef.user("user", "eve");

        Zookie z1 = store.write(doc, "viewer", eve);     // grant
        Zookie z2 = store.delete(doc, "viewer", eve);     // revoke
        Zookie z3 = store.write(doc, "viewer", eve);      // re-grant

        assertTrue(engine.check(doc, "viewer", eve, z1), "Granted at z1");
        assertFalse(engine.check(doc, "viewer", eve, z2), "Revoked at z2");
        assertTrue(engine.check(doc, "viewer", eve, z3), "Re-granted at z3");
    }

    @Test
    void multipleUsersRevokedAtDifferentTimes() {
        var store = new InMemoryTupleStore();
        var engine = new CheckEngine(store, Map.of());

        ObjectRef doc = new ObjectRef("doc", "report");
        SubjectRef alice = SubjectRef.user("user", "alice");
        SubjectRef bob = SubjectRef.user("user", "bob");

        Zookie z1 = store.write(doc, "viewer", alice);
        Zookie z2 = store.write(doc, "viewer", bob);
        Zookie z3 = store.delete(doc, "viewer", alice);

        assertTrue(engine.check(doc, "viewer", alice, z2),
                "Alice still has access before her revocation");
        assertFalse(engine.check(doc, "viewer", alice, z3),
                "Alice denied after her revocation");
        assertTrue(engine.check(doc, "viewer", bob, z3),
                "Bob still has access — only Alice was revoked");
    }
}
