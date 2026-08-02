package dev.zanzibar.leopard;

import dev.zanzibar.ZanzibarEngine;
import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.consistency.Consistency;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration: with {@code .enableLeopard()} the facade feeds the index
 * off the write path, and {@code membership(...)} answers via Leopard, staying in
 * agreement with the recursive {@code check(...)} path.
 */
class ZanzibarEngineLeopardTest {

    private final ObjectRef g0 = new ObjectRef("group", "g0");
    private final ObjectRef g1 = new ObjectRef("group", "g1");
    private final ObjectRef g2 = new ObjectRef("group", "g2");
    private final SubjectRef u0 = SubjectRef.user("user", "u0");
    private final SubjectRef u1 = SubjectRef.user("user", "u1");

    private ZanzibarEngine newEngine() {
        return ZanzibarEngine.builder()
                .namespace(NamespaceConfig.builder("group")
                        .relation("member", RewriteRule.thisRelation()).build())
                .enableLeopard()
                .build();
    }

    @Test
    void membershipFastPathAgreesWithRecursiveCheck() {
        var engine = newEngine();

        // u0 ∈ g0 ⊂ g1 ⊂ g2
        engine.write("group", "g0", "member", "user", "u0");
        engine.writeUserset("group", "g1", "member", "group", "g0", "member");
        engine.writeUserset("group", "g2", "member", "group", "g1", "member");

        // Synchronous feed ⇒ index is fully fresh ⇒ fully-consistent uses Leopard.
        var answer = engine.membership(u0, g2, Consistency.fullyConsistent());
        assertEquals(LeopardMembershipService.Source.LEOPARD, answer.source());
        assertTrue(answer.member());
        // Cross-verify against the authoritative recursive path.
        assertTrue(engine.check(g2, "member", u0));

        var nonMember = engine.membership(u1, g2, Consistency.fullyConsistent());
        assertFalse(nonMember.member());
        assertFalse(engine.check(g2, "member", u1));
    }

    @Test
    void membershipReflectsDeletesThroughTheChangeFeed() {
        var engine = newEngine();
        engine.write("group", "g0", "member", "user", "u0");
        engine.writeUserset("group", "g1", "member", "group", "g0", "member");

        assertTrue(engine.membership(u0, g1, Consistency.fullyConsistent()).member());

        // Sever g1's link to g0; the facade forwards the delete to the index.
        engine.delete(g1, "member", SubjectRef.userset("group", "g0", "member"));

        assertFalse(engine.membership(u0, g1, Consistency.fullyConsistent()).member());
        assertFalse(engine.check(g1, "member", u0));
    }

    @Test
    void membershipThrowsWhenLeopardDisabled() {
        var engine = ZanzibarEngine.builder()
                .namespace(NamespaceConfig.builder("group")
                        .relation("member", RewriteRule.thisRelation()).build())
                .build();
        assertThrows(IllegalStateException.class,
                () -> engine.membership(u0, g0, Consistency.fullyConsistent()));
    }
}
