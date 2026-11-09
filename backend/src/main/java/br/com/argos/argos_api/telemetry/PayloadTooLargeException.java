package br.com.argos.argos_api.telemetry;

public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(int maxBytes) {
        super("Corpo maior que " + maxBytes + " bytes");
    }
}
