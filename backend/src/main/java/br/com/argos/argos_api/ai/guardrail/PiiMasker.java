package br.com.argos.argos_api.ai.guardrail;

import java.util.EnumMap;
import java.util.Map;

/**
 * LGPD guardrail facade inside {@code ai.guardrail}, delegating to {@link br.com.argos.argos_api.shared.pii.PiiMasker}.
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

    private PiiMasker() {
    }

    public static Masked mask(String text) {
        br.com.argos.argos_api.shared.pii.PiiMasker.Masked result =
                br.com.argos.argos_api.shared.pii.PiiMasker.mask(text);
        if (result.counts().isEmpty()) {
            return new Masked(result.text(), Map.of());
        }
        Map<Kind, Integer> mapped = new EnumMap<>(Kind.class);
        result.counts().forEach((k, v) -> mapped.put(Kind.valueOf(k.name()), v));
        return new Masked(result.text(), Map.copyOf(mapped));
    }

    public static String maskText(String text) {
        return br.com.argos.argos_api.shared.pii.PiiMasker.maskText(text);
    }
}
