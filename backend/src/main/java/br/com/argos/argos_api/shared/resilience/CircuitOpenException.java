package br.com.argos.argos_api.shared.resilience;

/** The dependency failed repeatedly and is being skipped until its circuit breaker lets a trial call through. */
public class CircuitOpenException extends RuntimeException {

    private final String dependency;

    public CircuitOpenException(String dependency) {
        super("Circuito aberto para " + dependency + ": dependência indisponível no momento");
        this.dependency = dependency;
    }

    public String dependency() {
        return dependency;
    }
}
