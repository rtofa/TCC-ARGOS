package br.com.argos.argos_api.ai.guardrail;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LGPD guardrail: masks personal data and secrets before any text reaches the LLM (tool results, user questions)
 * and before any answer is stored. Patterns favour recall: masking a false positive costs little, leaking costs a lot.
 */
public final class PiiMasker {

    public enum Kind { EMAIL, CPF, CNPJ, CARTAO, TELEFONE, SEGREDO, IP }

    public record Masked(String text, Map<Kind, Integer> counts) {
        public boolean changed() {
            return !counts.isEmpty();
        }

        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(argos_[A-Za-z0-9_\\-]{8,}|sk-ant-[A-Za-z0-9_\\-]{8,}|bearer\\s+[A-Za-z0-9._\\-]{16,}"
                    + "|eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}"
                    + "|(?:password|senha|secret|token|api[_-]?key)\\s*[=:]\\s*[^\\s,;\"']{4,})");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern CNPJ = Pattern.compile("\\b\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}\\b");
    private static final Pattern CPF = Pattern.compile("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ \\-]?){13,19}\\b");
    private static final Pattern PHONE = Pattern.compile("(?:\\+?55\\s?)?\\(?\\b[1-9]{2}\\)?\\s?9?\\d{4}[\\s\\-]?\\d{4}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    private PiiMasker() {
    }

    public static Masked mask(String text) {
        if (text == null || text.isEmpty()) {
            return new Masked(text, Map.of());
        }
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        String result = replace(text, SECRET, Kind.SEGREDO, counts, match -> "[segredo]");
        result = replace(result, EMAIL, Kind.EMAIL, counts, match -> "[email]");
        result = replace(result, CNPJ, Kind.CNPJ, counts, match -> "[cnpj]");
        result = replace(result, CPF, Kind.CPF, counts, match -> "[cpf]");
        result = replaceCards(result, counts);
        result = replace(result, PHONE, Kind.TELEFONE, counts, match -> "[telefone]");
        result = replace(result, IPV4, Kind.IP, counts, match -> match.group(1) + "." + match.group(2) + ".x.x");
        return new Masked(result, Map.copyOf(counts));
    }

    public static String maskText(String text) {
        return mask(text).text();
    }

    private static String replace(String text, Pattern pattern, Kind kind, Map<Kind, Integer> counts,
                                  java.util.function.Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int found = 0;
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.apply(matcher)));
            found++;
        }
        if (found == 0) {
            return text;
        }
        matcher.appendTail(out);
        counts.merge(kind, found, Integer::sum);
        return out.toString();
    }

    /** Only digit runs that pass the Luhn check are cards; ids and timestamps of the same length are kept. */
    private static String replaceCards(String text, Map<Kind, Integer> counts) {
        Matcher matcher = CARD.matcher(text);
        StringBuilder out = new StringBuilder();
        int found = 0;
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[ \\-]", "");
            boolean card = digits.length() >= 13 && digits.length() <= 19 && luhn(digits);
            matcher.appendReplacement(out, Matcher.quoteReplacement(card ? "[cartao]" : matcher.group()));
            if (card) {
                found++;
            }
        }
        matcher.appendTail(out);
        if (found > 0) {
            counts.merge(Kind.CARTAO, found, Integer::sum);
        }
        return out.toString();
    }

    private static boolean luhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
