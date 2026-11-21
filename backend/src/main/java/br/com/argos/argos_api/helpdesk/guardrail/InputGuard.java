package br.com.argos.argos_api.helpdesk.guardrail;

import br.com.argos.argos_api.ai.guardrail.PromptInjectionDetector;
import br.com.argos.argos_api.helpdesk.model.ChatTurn;
import br.com.argos.argos_api.helpdesk.model.SupportRequest;
import br.com.argos.argos_api.shared.pii.PiiMasker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Input guardrail, the first stage of every question (deterministic, no model involved):
 *
 * <ul>
 *   <li>Prompt injection: attempts to override the agent's rules, reveal its prompt or change its role are blocked.</li>
 *   <li>Personal data (LGPD): e-mails, CPFs, cards, phones and secrets are masked before the question reaches any
 *       external API or log.</li>
 *   <li>History: the client sends it, so it is untrusted. Suspicious turns are dropped, the rest is masked, trimmed
 *       and limited; it is only used to understand follow-ups.</li>
 * </ul>
 *
 * Questions about other subjects are refused by the query-understanding step, and anything the documents do not
 * cover ends as "não encontrei" after retrieval.
 */
public class InputGuard {

    public record Verdict(boolean allowed, String reason, String question, List<ChatTurn> history, int maskedItems,
                          int droppedTurns) {
    }

    /** Extra patterns specific to a support assistant, on top of the shared detector. */
    private static final List<Pattern> EXTRA_PATTERNS = List.of(
            Pattern.compile("(finja|fing[ai]r?|pretend)\\s+(que|ser|to be)"),
            Pattern.compile("(responda|aja|atue|comporte-se)\\s+como\\s+(se|um|uma)"),
            Pattern.compile("sem\\s+(restri[cç][oõ]es|filtros|censura|limites)"),
            Pattern.compile("\\b(dan|do anything now)\\b"),
            Pattern.compile("(novas|nova)\\s+instru[cç][oõ]es"),
            Pattern.compile("(revele|mostre|repita|exiba|imprima|copie)\\s+(o\\s+|a\\s+|os\\s+|as\\s+)?(seu|sua|seus|suas)?\\s*"
                    + "(prompt|instru[cç])"),
            Pattern.compile("(override|bypass|contorne|burle)\\s+(the\\s+|as\\s+|os\\s+)?(rules|regras|guardrails|filtros)"));

    private static final int MAX_HISTORY_CHARS = 1000;

    private final int maxQuestionChars;
    private final int maxHistoryTurns;

    public InputGuard(int maxQuestionChars, int maxHistoryTurns) {
        this.maxQuestionChars = maxQuestionChars;
        this.maxHistoryTurns = maxHistoryTurns;
    }

    public Verdict check(SupportRequest request) {
        String question = normalize(request.question());
        if (question.isEmpty()) {
            return blocked("pergunta vazia");
        }
        if (question.length() > maxQuestionChars) {
            return blocked("pergunta longa demais");
        }
        Optional<String> injection = injection(question);
        if (injection.isPresent()) {
            return blocked("tentativa de prompt injection: \"" + injection.get() + "\"");
        }
        PiiMasker.Masked masked = PiiMasker.mask(question);

        List<ChatTurn> history = new ArrayList<>();
        int dropped = 0;
        int maskedItems = masked.total();
        List<ChatTurn> recent = request.historyOrEmpty();
        recent = recent.subList(Math.max(0, recent.size() - maxHistoryTurns), recent.size());
        for (ChatTurn turn : recent) {
            String content = normalize(turn.content());
            if (content.isEmpty() || injection(content).isPresent()) {
                dropped++;
                continue;
            }
            PiiMasker.Masked maskedTurn = PiiMasker.mask(content);
            maskedItems += maskedTurn.total();
            String text = maskedTurn.text();
            history.add(new ChatTurn(turn.role(), text.length() > MAX_HISTORY_CHARS ? text.substring(0, MAX_HISTORY_CHARS) + "…" : text));
        }
        return new Verdict(true, null, masked.text(), List.copyOf(history), maskedItems, dropped);
    }

    static Optional<String> injection(String text) {
        Optional<String> shared = PromptInjectionDetector.detect(text);
        if (shared.isPresent()) {
            return shared;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (Pattern pattern : EXTRA_PATTERNS) {
            var matcher = pattern.matcher(normalized);
            if (matcher.find()) {
                return Optional.of(matcher.group());
            }
        }
        return Optional.empty();
    }

    /** Trims, drops control characters (except new lines) and collapses runs of spaces. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").replaceAll("[ \\t]+", " ").strip();
    }

    private static Verdict blocked(String reason) {
        return new Verdict(false, reason, null, List.of(), 0, 0);
    }
}
