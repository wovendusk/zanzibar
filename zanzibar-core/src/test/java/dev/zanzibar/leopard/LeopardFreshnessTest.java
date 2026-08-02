package dev.zanzibar.leopard;

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
 * The consistency contract of the accelerator: Leopard is used only when it is
 * fresh enough for the requested revision, otherwise the service falls back to the
 * authoritative engine — and either way the answer honors the freshness bound.
 */
class LeopardFreshnessTest {

    private final ObjectRef g0 = new ObjectRef("group", "g0");
    private final SubjectRef u0 = SubjectRef.user("user", "u0");
    private final SubjectRef u1 = SubjectRef.user("user", "u1");

    @Test
    void usesLeopardWhenFresh_fallsBackWhenStale() {
        var store = new InMemoryTupleStore();
        var engine = new CheckEngine(store,
                Map.of("group", NamespaceConfig.builder("group")
                        .relation("member", RewriteRule.thisRelation()).build()));
        var index = new LeopardIndex("group", "member");
        var service = new LeopardMembershipService(index,
                (member, group, atRevision) ->
                        engine.check(group, "member", member, new Zookie(atRevision)));

        // rev 1: u0 joins g0, and the index sees it → index fresh through rev 1.
        Zookie z1 = store.write(g0, "member", u0);
        index.applyWrite(g0, "member", u0, z1.revision());

        // rev 2: u1 joins g0 in the store, but the change-feed to the index LAGS —
        // the index does NOT see it. indexedThrough stays at 1.
        Zookie z2 = store.write(g0, "member", u1);

        assertEquals(1, index.indexedThrough(), "index has not consumed rev 2 yet");

        // A query needing rev-2 freshness for u1 must fall back (index too stale)
        // and get the correct fresh answer from the engine.
        var fresh = service.isMember(u1, g0, z2.revision());
        assertEquals(LeopardMembershipService.Source.FALLBACK, fresh.source());
        assertTrue(fresh.member(), "engine sees u1 as a member at rev 2");

        // A query needing only rev-1 freshness may use Leopard. At rev 1, u1 was not
        // yet a member, so Leopard's (rev-1) answer of false honors the bound.
        var stale = service.isMember(u1, g0, z1.revision());
        assertEquals(LeopardMembershipService.Source.LEOPARD, stale.source());
        assertFalse(stale.member());

        // u0 was a member at rev 1 and the index knows it → Leopard fast path, true.
        var known = service.isMember(u0, g0, z1.revision());
        assertEquals(LeopardMembershipService.Source.LEOPARD, known.source());
        assertTrue(known.member());
    }
}
