package br.com.argos.argos_api.alerting;

public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(int maxBytes) {
        super("Corpo maior que " + maxBytes + " bytes");
    }
}
