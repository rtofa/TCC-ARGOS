package br.com.argos.argos_api.ai.guardrail;

import br.com.argos.argos_api.ai.agent.AgentContext;
import br.com.argos.argos_api.ai.agent.InvestigationReport;
import br.com.argos.argos_api.ai.agent.ReportValidator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Output guardrail of the investigator. A bad answer is sent back to the agent with what to fix (reprompt), keeping
 * the whole tool history, instead of being stored:
 * <ol>
 *   <li>format: a JSON object that follows the contract;</li>
 *   <li>grounding: deploys, traces and services cited must have been observed; every linked alert is explained;</li>
 *   <li>LGPD: personal data that slipped into the answer is masked before it is accepted.</li>
 * </ol>
 */
public class InvestigationReportGuardrail implements OutputGuardrail {

    private final ReportValidator validator;

    public InvestigationReportGuardrail(ReportValidator validator) {
        this.validator = validator;
    }

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        AgentContext context = AgentContext.current();
        var parsed = validator.parse(responseFromLLM.text());
        if (parsed.isEmpty()) {
            context.guardrail("FORMATO_INVALIDO", "a resposta não era um JSON válido");
            return reprompt("Resposta fora do formato",
                    "Sua resposta não é um objeto JSON válido. Responda novamente apenas com o JSON do relatório, "
                            + "no formato descrito nas instruções, sem texto antes ou depois.");
        }
        InvestigationReport report = parsed.get();
        context.rememberCandidate(report);

        List<String> problems = new ArrayList<>(validator.structuralProblems(report));
        problems.addAll(validator.groundingProblems(report, context));
        if (!problems.isEmpty()) {
            context.guardrail("RELATORIO_CORRIGIDO", String.join("; ", problems));
            return reprompt("Relatório inconsistente com os dados",
                    "Corrija o relatório antes de entregar:\n- " + String.join("\n- ", problems)
                            + "\nCite somente versões, commits, traces e serviços que apareceram nos dados. Se algo "
                            + "não puder ser verificado, diga isso e reduza a confiança. Responda apenas com o JSON.");
        }

        int[] masked = {0};
        InvestigationReport safe = report.mapText(text -> {
            PiiMasker.Masked result = PiiMasker.mask(text);
            masked[0] += result.total();
            return result.text();
        });
        if (masked[0] > 0) {
            context.guardrail("PII_NA_RESPOSTA", masked[0] + " dado(s) pessoal(is) mascarado(s) no relatório");
        }
        context.acceptReport(safe);
        return successWith(validator.toJson(safe));
    }
}
