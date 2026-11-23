package br.com.argos.argos_api.incident.dto;

import br.com.argos.argos_api.incident.domain.Severity;

import java.time.LocalDate;

public record CalendarDayResponse(LocalDate date, long count, Severity highestSeverity) {
}
