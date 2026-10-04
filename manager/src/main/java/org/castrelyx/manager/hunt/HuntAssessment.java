package org.castrelyx.manager.hunt;

import java.util.List;

/** Confidence describes the classification evidence, never a probability of compromise. */
public record HuntAssessment(String category, String priority, String title, String explanation,
    String evidenceLevel, List<String> evidence, List<String> nextSteps, String caveat, String ruleId) {}
