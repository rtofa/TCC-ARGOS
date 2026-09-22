package br.com.argos.argos_api.identity;

public record RegisterRequest(String organizationName, String userName, String email, String password) {}
