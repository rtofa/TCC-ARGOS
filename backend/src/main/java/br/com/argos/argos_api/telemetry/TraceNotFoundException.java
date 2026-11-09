package br.com.argos.argos_api.telemetry;

public class TraceNotFoundException extends RuntimeException {

    public TraceNotFoundException(String traceId) {
        super("Trace nao encontrado: " + traceId);
    }
}
