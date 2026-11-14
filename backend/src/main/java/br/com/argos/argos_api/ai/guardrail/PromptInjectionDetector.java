package br.com.argos.argos_api.ai.guardrail;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Heuristic prompt-injection detector. Logs, span attributes and alert payloads are written by whoever can reach
 * the monitored systems, so they are treated as hostile input: a log line saying "ignore your instructions" must
 * never steer the agent.
 */
public final class PromptInjectionDetector {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("ignor(e|ar|e as|e todas as)\\s+(as\\s+|todas\\s+as\\s+)?(instru|regras|orienta)"),
            Pattern.compile("ignore\\s+(all\\s+|any\\s+|the\\s+)?(previous|prior|above)\\s+(instructions|rules)"),
            Pattern.compile("disregard\\s+(all\\s+|the\\s+)?(previous|prior|above)"),
            Pattern.compile("(esque[cç]a|desconsidere)\\s+(tudo|as\\s+instru|suas\\s+regras|o\\s+que)"),
            Pattern.compile("(voc[eê]\\s+agora\\s+[eé]|a\\s+partir\\s+de\\s+agora\\s+voc[eê]|you\\s+are\\s+now|act\\s+as)"),
            Pattern.compile("(system\\s*prompt|prompt\\s+do\\s+sistema|developer\\s+mode|modo\\s+desenvolvedor|jailbreak)"),
            Pattern.compile("(revele|mostre|repita|print|reveal)\\s+(o\\s+|seu\\s+|suas\\s+|your\\s+)?(prompt|instru|system)"),
            Pattern.compile("</?\\s*(system|dados|instructions|assistant)\\s*>"));

    private PromptInjectionDetector() {
    }

    /** Returns the suspicious fragment when the text looks like an attempt to give the model instructions. */
    public static Optional<String> detect(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (Pattern pattern : PATTERNS) {
            var matcher = pattern.matcher(normalized);
            if (matcher.find()) {
                return Optional.of(matcher.group());
            }
        }
        return Optional.empty();
    }

    /**
     * Defuses untrusted data before it goes into a tool result: tags that could close the {@code <dados>} wrapper
     * are broken so the data cannot pose as instructions.
     */
    public static String neutralize(String text) {
        return text == null ? "" : text.replaceAll("(?i)<\\s*(/?)\\s*(dados|system|instructions|assistant)\\s*>", "‹$1$2›");
    }
}
