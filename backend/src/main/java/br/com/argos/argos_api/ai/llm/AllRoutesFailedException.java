package br.com.argos.argos_api.ai.llm;

import java.util.List;

/** Every model in the chain failed or was skipped; callers fall back to the heuristic investigator. */
public class AllRoutesFailedException extends RuntimeException {

    private final List<String> failures;

    public AllRoutesFailedException(List<String> failures, Throwable lastError) {
        super(failures.isEmpty() ? "Nenhum modelo configurado" : "Todos os modelos falharam: " + String.join("; ", failures),
                lastError);
        this.failures = List.copyOf(failures);
    }

    public List<String> failures() {
        return failures;
    }
}
