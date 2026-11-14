package br.com.argos.argos_api.ai.guardrail;

import br.com.argos.argos_api.ai.agent.AgentContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;

/** Guardrails of the incident assistant (follow-up questions about an investigation). */
public final class AssistantGuardrails {

    public static final int MAX_QUESTION_LENGTH = 2000;
    public static final String INJECTION_REFUSAL = "Não posso seguir instruções que tentam mudar minhas regras ou "
            + "revelar minha configuração. Posso ajudar com perguntas sobre este incidente e os dados dele.";

    private AssistantGuardrails() {
    }

    /** Blocks oversized questions and injection attempts; masks personal data before it reaches the model. */
    public static class Input implements InputGuardrail {

        @Override
        public InputGuardrailResult validate(UserMessage userMessage) {
            String text = userMessage.singleText();
            if (text == null || text.isBlank()) {
                return fatal("Pergunta vazia");
            }
            if (text.length() > MAX_QUESTION_LENGTH) {
                return fatal("Pergunta longa demais (máximo de " + MAX_QUESTION_LENGTH + " caracteres)");
            }
            var injection = PromptInjectionDetector.detect(text);
            if (injection.isPresent()) {
                AgentContext.currentIfAny().ifPresent(context ->
                        context.guardrail("INJECAO_BLOQUEADA", "pergunta do usuário: \"" + injection.get() + "\""));
                return fatal(INJECTION_REFUSAL);
            }
            PiiMasker.Masked masked = PiiMasker.mask(text);
            if (masked.changed()) {
                AgentContext.currentIfAny().ifPresent(context ->
                        context.guardrail("PII_MASCARADA", "pergunta do usuário: " + masked.counts()));
                return successWith(masked.text());
            }
            return success();
        }
    }

    /** Masks personal data that the answer may echo from logs or traces. */
    public static class Output implements OutputGuardrail {

        @Override
        public OutputGuardrailResult validate(AiMessage responseFromLLM) {
            String text = responseFromLLM.text();
            if (text == null || text.isBlank()) {
                return reprompt("Resposta vazia", "Responda à pergunta do usuário em texto, em português.");
            }
            PiiMasker.Masked masked = PiiMasker.mask(text);
            if (masked.changed()) {
                AgentContext.currentIfAny().ifPresent(context ->
                        context.guardrail("PII_NA_RESPOSTA", masked.total() + " dado(s) mascarado(s) na resposta"));
                return successWith(masked.text());
            }
            return success();
        }
    }
}
