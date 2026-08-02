package dev.zanzibar.trace;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects decision trace steps during a check evaluation.
 * Thread-confined: create one per check invocation.
 */
public class TraceCollector {

    private final List<TraceStep> steps = new ArrayList<>();

    public void recordDirectCheck(ObjectRef resource, String relation,
                                  SubjectRef subject, boolean found) {
        steps.add(new TraceStep.DirectCheck(resource, relation, subject, found));
    }

    public void recordGroupIndirection(ObjectRef resource, String relation,
                                       SubjectRef groupSubject, SubjectRef targetSubject,
                                       boolean resolved) {
        steps.add(new TraceStep.GroupIndirection(resource, relation,
                groupSubject, targetSubject, resolved));
    }

    public void recordComputedUserset(ObjectRef resource, String fromRelation, String toRelation) {
        steps.add(new TraceStep.ComputedUserset(resource, fromRelation, toRelation));
    }

    public void recordTupleToUserset(ObjectRef resource, String tuplesetRelation,
                                     ObjectRef parent, String computedRelation) {
        steps.add(new TraceStep.TupleToUserset(resource, tuplesetRelation, parent, computedRelation));
    }

    public void recordSetOperation(String operation, ObjectRef resource, String relation) {
        steps.add(new TraceStep.SetOperation(operation, resource, relation));
    }

    public void recordCacheHit(ObjectRef resource, String relation,
                               SubjectRef subject, boolean result) {
        steps.add(new TraceStep.CacheHit(resource, relation, subject, result));
    }

    public void recordResult(ObjectRef resource, String relation,
                             SubjectRef subject, boolean granted) {
        steps.add(new TraceStep.Result(resource, relation, subject, granted));
    }

    public List<TraceStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    /**
     * Serialize the trace to a structured text format suitable for LLM consumption.
     */
    public String toStructuredText() {
        var sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            sb.append(i + 1).append(". ");
            switch (steps.get(i)) {
                case TraceStep.DirectCheck d ->
                    sb.append(d.found() ? "FOUND" : "NOT_FOUND")
                      .append(": ").append(d.resource()).append("#").append(d.relation())
                      .append("@").append(d.subject());
                case TraceStep.GroupIndirection g ->
                    sb.append("GROUP_CHECK: ").append(g.resource()).append("#").append(g.relation())
                      .append(" via ").append(g.groupSubject())
                      .append(" for ").append(g.targetSubject())
                      .append(" → ").append(g.resolved() ? "resolved" : "not resolved");
                case TraceStep.ComputedUserset c ->
                    sb.append("COMPUTED_USERSET: ").append(c.resource())
                      .append(" checking ").append(c.toRelation())
                      .append(" (because ").append(c.toRelation())
                      .append("s are also ").append(c.fromRelation()).append("s)");
                case TraceStep.TupleToUserset t ->
                    sb.append("TUPLE_TO_USERSET: ").append(t.resource())
                      .append("#").append(t.tuplesetRelation())
                      .append(" → parent ").append(t.parent())
                      .append(" → checking ").append(t.computedRelation());
                case TraceStep.SetOperation s ->
                    sb.append("SET_OP: ").append(s.operation())
                      .append(" on ").append(s.resource()).append("#").append(s.relation());
                case TraceStep.CacheHit ch ->
                    sb.append("CACHE_HIT: ").append(ch.resource()).append("#").append(ch.relation())
                      .append("@").append(ch.subject())
                      .append(" = ").append(ch.result());
                case TraceStep.Result r ->
                    sb.append("RESULT: ").append(r.resource()).append("#").append(r.relation())
                      .append("@").append(r.subject())
                      .append(" → ").append(r.granted() ? "GRANTED" : "DENIED");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
