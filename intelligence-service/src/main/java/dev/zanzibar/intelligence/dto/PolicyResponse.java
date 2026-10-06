package dev.zanzibar.intelligence.dto;

import java.util.List;

/**
 * @param configJson      the namespace config document the model produced
 * @param suggestedTuples example tuples the model proposed; they are not written
 * @param valid           whether the ACL service accepted the document
 * @param error           why it was rejected, or null
 * @param installed       whether the config was put into force
 * @param attempts        how many times the model was asked
 */
public record PolicyResponse(String configJson, List<String> suggestedTuples,
                             boolean valid, String error, boolean installed, int attempts) {
}
