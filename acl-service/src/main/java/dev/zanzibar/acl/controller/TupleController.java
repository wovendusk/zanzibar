package dev.zanzibar.acl.controller;

import dev.zanzibar.ZanzibarEngine;
import dev.zanzibar.acl.dto.TupleRequest;
import dev.zanzibar.acl.dto.ZookieResponse;
import dev.zanzibar.acl.kafka.PermissionEventPublisher;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tuples")
public class TupleController {

    private final ZanzibarEngine engine;
    private final PermissionEventPublisher publisher;

    public TupleController(ZanzibarEngine engine, PermissionEventPublisher publisher) {
        this.engine = engine;
        this.publisher = publisher;
    }

    @PostMapping
    public ResponseEntity<ZookieResponse> write(@RequestBody TupleRequest req) {
        ObjectRef resource = req.toObjectRef();
        SubjectRef subject = req.toSubjectRef();
        Zookie z = engine.write(resource, req.relation(), subject);
        publisher.publishWrite(resource, req.relation(), subject, z);
        return ResponseEntity.ok(new ZookieResponse(z.revision()));
    }

    @DeleteMapping
    public ResponseEntity<ZookieResponse> delete(@RequestBody TupleRequest req) {
        ObjectRef resource = req.toObjectRef();
        SubjectRef subject = req.toSubjectRef();
        Zookie z = engine.delete(resource, req.relation(), subject);
        publisher.publishDelete(resource, req.relation(), subject, z);
        return ResponseEntity.ok(new ZookieResponse(z.revision()));
    }

    @GetMapping
    public ResponseEntity<List<RelationTuple>> read(
            @RequestParam String resourceNs,
            @RequestParam String resourceId,
            @RequestParam String relation,
            @RequestParam(required = false) Long zookieRevision) {
        ObjectRef resource = new ObjectRef(resourceNs, resourceId);
        if (zookieRevision != null) {
            return ResponseEntity.ok(engine.read(resource, relation, new Zookie(zookieRevision)));
        }
        return ResponseEntity.ok(engine.read(resource, relation));
    }
}
