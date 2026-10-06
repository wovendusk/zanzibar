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
                  "relations": { "<relation name>": <rule>, ... } }
              ],
              "tuples": [ "<object>#<relation>@<subject>", ... ]
            }

            A <rule> must be exactly one of these six building blocks:

            1. {"type": "this"}
               Whoever is listed directly in tuples for this relation.
            2. {"type": "computed_userset", "relation": "<other relation>"}
               Everyone who has <other relation> on the same object.
               Example: editors are also viewers.
            3. {"type": "tuple_to_userset", "tupleset": "<link relation>", "computed": "<relation>"}
               Follow <link relation> to another object and take <relation> there.
               Example: viewers of the parent folder are viewers of the document.
            4. {"type": "union", "children": [<rule>, ...]}
               Granted if any child grants.
            5. {"type": "intersection", "children": [<rule>, ...]}
               Granted only if every child grants.
            6. {"type": "exclusion", "base": <rule>, "subtract": <rule>}
               Granted by base unless subtract also grants.

            Constraints:
            - Use only these six building blocks. Do not invent other types or fields.
            - Every relation named in a computed_userset, or as the tupleset of a
              tuple_to_userset, must be defined in the same namespace.
            - "relation", "tupleset" and "computed" are plain relation names such as
              "parent" or "member". They never contain ":" or "#".
            - To link an object to another object (a page to its project, a document
              to its folder), give it a link relation of type "this", for example
              "project" or "parent", and use that as the tupleset.
            - "X are also Y" means Y is a union containing a computed_userset of X.
            - "unless", "except" or "but not" means an exclusion.
            - A relation that people or groups are assigned to directly needs {"type": "this"},
              on its own or inside a union.
            - Groups are a namespace "group" with a relation "member" of type "this".
              A whole group as a subject is written "group:<name>#member".
            - "tuples" lists example tuples that put the policy into effect, such as
              "folder:engineering#viewer@group:engineers#member" or
              "doc:spec#parent@folder:engineering". Use an empty list if none are implied.
            - Prefer the simplest configuration that expresses the policy.

            Example. Policy: "Engineers can view every document in the engineering folder."

            {
              "namespaces": [
                { "name": "group", "relations": { "member": {"type": "this"} } },
                { "name": "folder", "relations": { "viewer": {"type": "this"} } },
                { "name": "doc", "relations": {
                    "parent": {"type": "this"},
                    "viewer": {"type": "union", "children": [
                      {"type": "this"},
                      {"type": "tuple_to_userset", "tupleset": "parent", "computed": "viewer"} ]} } }
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
                { "name": "group", "relations": { "member": {"type": "this"} } },
                { "name": "repo", "relations": {
                    "maintainer": {"type": "this"},
                    "contributor": {"type": "union", "children": [
                      {"type": "this"},
                      {"type": "computed_userset", "relation": "maintainer"} ]} } },
                { "name": "issue", "relations": {
                    "repo": {"type": "this"},
                    "blocked": {"type": "this"},
                    "reader": {"type": "exclusion",
                      "base": {"type": "tuple_to_userset", "tupleset": "repo", "computed": "contributor"},
                      "subtract": {"type": "computed_userset", "relation": "blocked"} } } }
              ],
              "tuples": [
                "issue:42#repo@repo:engine",
                "repo:engine#maintainer@user:alice"
              ]
            }
            """;
}
