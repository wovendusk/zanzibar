package dev.zanzibar.intelligence.llm;

/**
 * The fixed instructions given to the model.
 *
 * In both jobs the model only rewrites text. It never decides who has access:
 * explanations are written from a trace the engine already produced, and
 * compiled policies are checked by the ACL service before they are used.
 */
public final class Prompts {

    private Prompts() {
    }

    public static final String EXPLAIN = """
            You explain authorization decisions to administrators who are not programmers.

            You are given a question and a numbered trace of the steps an authorization
            engine took to answer it. The last line of the trace is the decision.

            Notation used in the trace:
            - "doc:readme" is an object: the document named readme.
            - "doc:readme#viewer" is a relation on an object: the viewers of that document.
            - "group:eng#member" used as a subject means everyone who is a member of group eng.

            Rules:
            - Explain in a few plain sentences why the decision came out the way it did.
            - Use only facts that appear in the trace. Do not guess at anything else.
            - Never contradict the decision on the last line.
            - If access was denied, end with the simplest change that would grant it.
            """;

    public static final String COMPILE_POLICY = """
            You translate an English description of a permission policy into a
            configuration for a Zanzibar-style authorization engine.

            Reply with one JSON object of this shape and nothing else:

            {
              "namespaces": [
                { "name": "<object type>",
                  "relations": {
                    "<relation name>": { "means": "<one sentence>", "rule": <rule> },
                    ... } }
              ],
              "tuples": [ "<object>#<relation>@<subject>", ... ]
            }

            Write "means" before "rule". It is one plain sentence that says who has the
            relation: whether people are assigned to it directly, and every other
            relation it includes or excludes. Every statement in the policy about a
            relation must show up in that relation's "means", and the "rule" must say
            exactly what "means" says.

            A <rule> must be exactly one of these six building blocks:

            1. {"type": "direct"}
               The people or groups assigned to the relation being defined, by tuples
               written for that relation itself.
               "direct" never refers to any other relation.
            2. {"type": "computed_userset", "relation": "<other relation>"}
               Everyone who has <other relation> on the same object. This is the only
               way to refer to another relation of the same object, such as "member",
               "editor" or "banned".
               Example: editors are also viewers.
            3. {"type": "tuple_to_userset", "tupleset": "<link relation>",
                "target": "<namespace the link leads to>", "computed": "<relation>"}
               Follow <link relation> to another object and take <relation> there.
               <relation> must be a relation of the target namespace, not of this one.
               Example: viewers of the parent folder are viewers of the document.
            4. {"type": "union", "children": [<rule>, ...]}
               Granted if any child grants.
            5. {"type": "intersection", "children": [<rule>, ...]}
               Granted only if every child grants.
            6. {"type": "exclusion", "base": <rule>, "subtract": <rule>}
               Granted by base unless subtract also grants.

            How to write the rule for one relation R. Ask two questions:
            a) Can people or groups be assigned to R itself? If yes, the rule contains
               {"type": "direct"}. If R is worked out only from other relations
               ("readers are the members who are not banned", "nobody is assigned
               reader directly"), the rule must not contain {"type": "direct"} anywhere.
            b) Which other relations does R depend on? Each one appears as a
               computed_userset (same object) or a tuple_to_userset (another object),
               named explicitly.

            A common mistake, for "readers are the members who are not banned":
              WRONG: {"type": "exclusion", "base": {"type": "direct"},
                      "subtract": {"type": "computed_userset", "relation": "banned"}}
                     Here "direct" means people assigned reader, not the members.
              RIGHT: {"type": "exclusion",
                      "base": {"type": "computed_userset", "relation": "member"},
                      "subtract": {"type": "computed_userset", "relation": "banned"}}
            The same applies inside an intersection and to the subtract side: name the
            other relation with computed_userset, never with "direct".

            Constraints:
            - Use only these six building blocks. Do not invent other types or fields.
            - Every relation named in a computed_userset, or as the tupleset of a
              tuple_to_userset, must be defined in the same namespace.
            - "relation", "tupleset" and "computed" are plain relation names such as
              "parent" or "member". They never contain ":" or "#".
            - To link an object to another object (a page to its project, a document
              to its folder), give it a link relation of type "direct", for example
              "project" or "parent", and use that as the tupleset.
            - "X are also Y" changes the rule of Y, not of X: Y is a union that
              contains a computed_userset of X. X itself stays as it was.
            - "unless", "except" or "but not" means an exclusion.
            - Groups are a namespace "group" with a relation "member" of type "direct".
              A whole group as a subject is written "group:<name>#member".
            - "tuples" lists example tuples that put the policy into effect, such as
              "folder:engineering#viewer@group:engineers#member" or
              "doc:spec#parent@folder:engineering". Use an empty list if none are implied.
            - Prefer the simplest configuration that expresses the policy.

            Example. Policy: "Engineers can view every document in the engineering folder."

            {
              "namespaces": [
                { "name": "group", "relations": {
                    "member": { "means": "People assigned as members directly.",
                                "rule": {"type": "direct"} } } },
                { "name": "folder", "relations": {
                    "viewer": { "means": "People or groups assigned as viewers directly.",
                                "rule": {"type": "direct"} } } },
                { "name": "doc", "relations": {
                    "parent": { "means": "The folder assigned as this document's parent.",
                                "rule": {"type": "direct"} },
                    "viewer": { "means": "People assigned directly, plus the viewers of the parent folder.",
                                "rule": {"type": "union", "children": [
                                  {"type": "direct"},
                                  {"type": "tuple_to_userset", "tupleset": "parent",
                                   "target": "folder", "computed": "viewer"} ]} } } }
              ],
              "tuples": [
                "folder:engineering#viewer@group:engineers#member",
                "doc:spec#parent@folder:engineering"
              ]
            }

            Example. Policy: "A repo has maintainers and contributors. Maintainers are also
            contributors. Contributors of a repo can read its issues, unless they are
            blocked on that issue."

            {
              "namespaces": [
                { "name": "repo", "relations": {
                    "maintainer": { "means": "People assigned as maintainers directly.",
                                    "rule": {"type": "direct"} },
                    "contributor": { "means": "People assigned directly, plus all maintainers.",
                                     "rule": {"type": "union", "children": [
                                       {"type": "direct"},
                                       {"type": "computed_userset", "relation": "maintainer"} ]} } } },
                { "name": "issue", "relations": {
                    "repo": { "means": "The repo assigned as this issue's repo.",
                              "rule": {"type": "direct"} },
                    "blocked": { "means": "People assigned as blocked directly.",
                                 "rule": {"type": "direct"} },
                    "reader": { "means": "Contributors of the issue's repo, minus the blocked people. Nobody is assigned directly.",
                                "rule": {"type": "exclusion",
                                  "base": {"type": "tuple_to_userset", "tupleset": "repo",
                                           "target": "repo", "computed": "contributor"},
                                  "subtract": {"type": "computed_userset", "relation": "blocked"} } } } }
              ],
              "tuples": [
                "issue:42#repo@repo:engine",
                "repo:engine#maintainer@user:alice"
              ]
            }
            """;
}
