package dev.zanzibar.acl.controller;

import dev.zanzibar.ZanzibarEngine;
import dev.zanzibar.acl.dto.TupleRequest;
import dev.zanzibar.acl.dto.ZookieResponse;
import dev.zanzibar.acl.service.TupleService;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.Zookie;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Write, delete and read relation tuples. */
@RestController
@RequestMapping("/api/v1/tuples")
public class TupleController {

    private final TupleService tuples;
    private final ZanzibarEngine engine;

    public TupleController(TupleService tuples, ZanzibarEngine engine) {
        this.tuples = tuples;
        this.engine = engine;
    }

    @PostMapping
    public ZookieResponse write(@RequestBody TupleRequest request) {
        Zookie zookie = tuples.write(request.toObjectRef(), request.relation(), request.toSubjectRef());
        return new ZookieResponse(zookie.revision());
    }

    @DeleteMapping
    public ZookieResponse delete(@RequestBody TupleRequest request) {
        Zookie zookie = tuples.delete(request.toObjectRef(), request.relation(), request.toSubjectRef());
        return new ZookieResponse(zookie.revision());
    }

    @GetMapping
    public List<RelationTuple> read(@RequestParam String resourceNs,
                                    @RequestParam String resourceId,
                                    @RequestParam String relation,
                                    @RequestParam(required = false) Long zookieRevision) {
        Zookie zookie = zookieRevision == null ? null : new Zookie(zookieRevision);
        return engine.read(new ObjectRef(resourceNs, resourceId), relation, zookie);
    }
}
