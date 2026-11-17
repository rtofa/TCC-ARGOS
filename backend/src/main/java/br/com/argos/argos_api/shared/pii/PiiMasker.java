package br.com.argos.argos_api.shared.pii;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizador LGPD compartilhado ({@code SEC-04} / guardrails de IA).
 * Mascara dados pessoais e segredos (CPF, CNPJ, e-mail, telefone, cartão de crédito, IPs e credenciais)
 * antes de persistir telemetria OTLP ou enviar contexto ao LLM.
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
                    + "|(?:password|passwd|senha|secret|token|api[_-]?key|authorization)\\s*[=:]\\s*[^\\s,;\"']{4,})");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern CNPJ = Pattern.compile("\\b\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}\\b");
    private static final Pattern CPF = Pattern.compile("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ \\-]?){13,19}\\b");
    private static final Pattern PHONE = Pattern.compile("(?:\\+?55\\s?)?\\(?\\b[1-9]{2}\\)?\\s?9?\\d{4}[\\s\\-]?\\d{4}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    private static final Set<String> SENSITIVE_ATTRIBUTE_KEYS = Set.of(
            "password", "passwd", "senha", "secret", "token", "api_key", "apikey", "authorization",
            "http.request.header.authorization", "http.request.header.cookie", "http.request.header.x-api-key",
            "cpf", "user.cpf", "customer.cpf", "cnpj", "credit_card", "card_number", "cartao", "cvv");

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

    /**
     * Mascara valores de atributos OTLP. Se a chave indicar explicitamente um segredo/documento,
     * oculta todo o valor; caso contrário, aplica a detecção automática de padrões LGPD no texto.
     */
    public static Object maskAttribute(String key, Object value) {
        if (value == null) {
            return null;
        }
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT).trim();
        if (SENSITIVE_ATTRIBUTE_KEYS.contains(normalizedKey)) {
            if (normalizedKey.contains("cpf")) {
                return "[cpf]";
            }
            if (normalizedKey.contains("cnpj")) {
                return "[cnpj]";
            }
            if (normalizedKey.contains("card") || normalizedKey.contains("cartao") || normalizedKey.equals("cvv")) {
                return "[cartao]";
            }
            return "[segredo]";
        }
        return value instanceof String s ? maskText(s) : value;
    }

    public static Map<String, Object> maskAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return attributes;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(attributes.size());
        attributes.forEach((k, v) -> sanitized.put(k, maskAttribute(k, v)));
        return sanitized;
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
