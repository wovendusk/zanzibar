package dev.zanzibar.trace;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

/**
 * A single step in a check decision trace.
 * The check engine records these as it traverses the rewrite tree,
 * producing a structured log that can be translated to English by an LLM.
 */
public sealed interface TraceStep {

    record DirectCheck(ObjectRef resource, String relation, SubjectRef subject, boolean found)
            implements TraceStep {}

    record GroupIndirection(ObjectRef resource, String relation,
                            SubjectRef groupSubject, SubjectRef targetSubject, boolean resolved)
            implements TraceStep {}

    record ComputedUserset(ObjectRef resource, String fromRelation, String toRelation)
            implements TraceStep {}

    record TupleToUserset(ObjectRef resource, String tuplesetRelation,
                          ObjectRef parent, String computedRelation)
            implements TraceStep {}

    record SetOperation(String operation, ObjectRef resource, String relation)
            implements TraceStep {}

    record CacheHit(ObjectRef resource, String relation, SubjectRef subject, boolean result)
            implements TraceStep {}

    record Result(ObjectRef resource, String relation, SubjectRef subject, boolean granted)
            implements TraceStep {}
}
