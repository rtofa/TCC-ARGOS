package br.com.argos.argos_api.helpdesk.model;

import java.util.List;

/** The final answer of the agent, with the documentation excerpts it cites and the execution trace. */
public record SupportAnswer(String id, Outcome outcome, String answer, List<SourceReference> sources, AnswerTrace trace) {
}
