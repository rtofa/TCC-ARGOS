package br.com.argos.argos_api.helpdesk.guardrail;

import br.com.argos.argos_api.helpdesk.model.RetrievedPassage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Output guardrail: an answer is only delivered when it is supported by the passages that were retrieved.
 *
 * <ol>
 *   <li>"Não encontrei…" is a legitimate answer (the model says the documents do not cover the question).</li>
 *   <li>The answer must cite at least one passage ({@code [n]}); citations to passages that do not exist are removed
 *       (an invented source is a hallucination signal).</li>
 *   <li>Groundedness: most of its content words must appear in the passages.</li>
 *   <li>Facts: numbers, codes, paths and identifiers in the answer must appear in the passages; these are the
 *       hallucinations that hurt most (a wrong port, header or endpoint).</li>
 *   <li>The answer must not leak the system prompt.</li>
 * </ol>
 *
 * A rejected answer is replaced by "não encontrei" instead of being shown.
 */
public class OutputGuard {

    public static final String NOT_FOUND_MESSAGE = "Não encontrei essa informação na documentação do Argos. "
            + "Se quiser, reformule a pergunta ou me pergunte sobre outro recurso da plataforma.";
    /** Marker inside the system prompt; seeing it in an answer means the prompt leaked. */
    public static final String PROMPT_CANARY = "ARGOS-HELPDESK-7f3a";

    public enum Status { GROUNDED, NOT_FOUND, REJECTED }

    public record Verdict(Status status, String answer, Set<Integer> citedPassages, double groundedness,
                          List<String> issues) {
    }

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2})]");
    private static final Pattern GROUPED_CITATION = Pattern.compile("\\[(\\d{1,2}(?:\\s*,\\s*\\d{1,2})+)]");
    private static final Pattern FULLWIDTH_CITATION = Pattern.compile("【\\s*(\\d{1,2})(?:[†:][^】]*)?】");
    /** Thousands separator between digits ("30.000" and "30000" are the same number). */
    private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d)[.,](?=\\d{3}(?!\\d))");
    /**
     * Fact-like tokens: numbers with 2+ digits, words mixing letters and digits (SEV1, V12, p95), API paths and
     * HTTP header names.
     */
    private static final Pattern FACT = Pattern.compile(
            "(/[a-zA-Z0-9_\\-{}]+(?:/[a-zA-Z0-9_\\-{}.]+)+)|\\b(X-[A-Za-z][A-Za-z0-9-]+)\\b|\\b(\\d{2,}(?:[.,]\\d+)?)\\b|\\b([A-Za-z]+\\d+[A-Za-z0-9]*)\\b");
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*\\d+[.)]\\s+");
    private static final int MIN_WORDS_FOR_RATIO = 6;

    private final double minGroundedness;
    private final boolean checkFacts;

    public OutputGuard(double minGroundedness, boolean checkFacts) {
        this.minGroundedness = minGroundedness;
        this.checkFacts = checkFacts;
    }

    public Verdict check(String answer, List<RetrievedPassage> passages) {
        String text = answer == null ? "" : answer.strip();
        if (text.isEmpty()) {
            return rejected(text, "resposta vazia");
        }
        if (PortugueseText.fold(text).startsWith("nao encontrei")) {
            return new Verdict(Status.NOT_FOUND, NOT_FOUND_MESSAGE, Set.of(), 1.0, List.of());
        }
        if (text.contains(PROMPT_CANARY)) {
            return rejected(text, "vazamento do prompt do sistema");
        }

        // 【1】 / 【1†fonte】 (some models) -> [1]; [1, 2] -> [1][2]; then drop citations to passages that do not exist.
        text = FULLWIDTH_CITATION.matcher(text).replaceAll("[$1]");
        text = GROUPED_CITATION.matcher(text).replaceAll(match ->
                String.join("", java.util.Arrays.stream(match.group(1).split(","))
                        .map(n -> "[" + n.strip() + "]").toList()));
        Set<Integer> available = new HashSet<>();
        passages.forEach(passage -> available.add(passage.index()));
        Set<Integer> cited = new TreeSet<>();
        List<String> issues = new ArrayList<>();
        Matcher matcher = CITATION.matcher(text);
        StringBuilder cleaned = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (available.contains(index)) {
                cited.add(index);
                matcher.appendReplacement(cleaned, Matcher.quoteReplacement(matcher.group()));
            } else {
                issues.add("citação inexistente [" + index + "] removida");
                matcher.appendReplacement(cleaned, "");
            }
        }
        matcher.appendTail(cleaned);
        text = cleaned.toString().replaceAll(" +([.,;:])", "$1").strip();

        if (cited.isEmpty()) {
            return rejected(text, join(issues, "nenhuma fonte citada"));
        }

        String evidence = PortugueseText.fold(String.join("\n", passages.stream()
                .map(p -> p.section().docTitle() + "\n" + p.section().sectionPath() + "\n" + p.content()).toList()));
        String claims = CITATION.matcher(text).replaceAll(" ");

        double groundedness = groundedness(claims, evidence);
        if (groundedness < minGroundedness) {
            issues.add("aderência %.2f abaixo do mínimo %.2f".formatted(groundedness, minGroundedness));
            return new Verdict(Status.REJECTED, text, cited, groundedness, List.copyOf(issues));
        }
        if (checkFacts) {
            Set<String> unsupported = unsupportedFacts(claims, evidence);
            if (!unsupported.isEmpty()) {
                issues.add("dados ausentes dos documentos: " + String.join(", ", unsupported));
                return new Verdict(Status.REJECTED, text, cited, groundedness, List.copyOf(issues));
            }
        }
        return new Verdict(Status.GROUNDED, text, cited, groundedness, List.copyOf(issues));
    }

    /** Share of the answer's content words (crude stems) found in the passages; 1.0 for very short answers. */
    static double groundedness(String claims, String foldedEvidence) {
        List<String> stems = PortugueseText.contentStems(claims);
        if (stems.size() < MIN_WORDS_FOR_RATIO) {
            return 1.0;
        }
        Set<String> evidenceStems = new HashSet<>(PortugueseText.contentStems(foldedEvidence));
        long supported = stems.stream().filter(evidenceStems::contains).count();
        return (double) supported / stems.size();
    }

    static Set<String> unsupportedFacts(String claims, String foldedEvidence) {
        String withoutListMarkers = LIST_MARKER.matcher(claims).replaceAll("");
        String compactEvidence = foldedEvidence.replaceAll("\\s+", " ");
        // Formatting must not count as a different fact: "10MB" = "10 MB", "30000" = "30.000".
        String looseEvidence = loose(compactEvidence);
        Set<String> unsupported = new LinkedHashSet<>();
        Matcher matcher = FACT.matcher(withoutListMarkers);
        while (matcher.find()) {
            String fact = matcher.group().strip();
            String folded = PortugueseText.fold(fact);
            boolean found = compactEvidence.contains(folded)
                    || compactEvidence.contains(folded.replace(',', '.'))
                    || compactEvidence.contains(folded.replace('.', ','))
                    || looseEvidence.contains(loose(folded));
            if (!found) {
                unsupported.add(fact);
            }
        }
        return unsupported;
    }

    private static String loose(String text) {
        return THOUSANDS.matcher(text).replaceAll("").replaceAll("\\s+", "");
    }

    private static Verdict rejected(String text, String issue) {
        return new Verdict(Status.REJECTED, text, Set.of(), 0.0, List.of(issue));
    }

    private static String join(List<String> issues, String last) {
        List<String> all = new ArrayList<>(issues);
        all.add(last);
        return String.join("; ", all);
    }
}
