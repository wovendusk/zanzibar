package dev.zanzibar.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * The steps the check engine took while answering one check, in order.
 * One trace belongs to one check and is used by one thread.
 */
public class DecisionTrace {

    private final List<String> steps = new ArrayList<>();

    public void add(String step) {
        steps.add(step);
    }

    public List<String> steps() {
        return List.copyOf(steps);
    }

    /** The steps as numbered lines of text. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            sb.append(i + 1).append(". ").append(steps.get(i)).append("\n");
        }
        return sb.toString();
    }
}
