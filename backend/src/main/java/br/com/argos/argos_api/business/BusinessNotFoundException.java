package br.com.argos.argos_api.business;

class BusinessNotFoundException extends RuntimeException {

    BusinessNotFoundException(String message) {
        super(message);
    }
}
