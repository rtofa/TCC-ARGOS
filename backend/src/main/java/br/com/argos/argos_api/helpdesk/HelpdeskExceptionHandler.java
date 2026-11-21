package br.com.argos.argos_api.helpdesk;

import br.com.argos.argos_api.helpdesk.ingestion.KnowledgeBaseIngestor;
import br.com.argos.argos_api.helpdesk.retrieval.KnowledgeBaseUnavailableException;
import br.com.argos.argos_api.helpdesk.store.KnowledgeStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Error responses (RFC 7807) of the support agent API. */
@RestControllerAdvice(basePackageClasses = HelpdeskController.class)
class HelpdeskExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(HelpdeskExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Corpo da requisição inválido");
    }

    @ExceptionHandler(HelpdeskBusyException.class)
    ResponseEntity<ProblemDetail> handleBusy(HelpdeskBusyException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "5")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()));
    }

    @ExceptionHandler({KnowledgeBaseUnavailableException.class, KnowledgeStoreException.class})
    ProblemDetail handleUnavailable(RuntimeException ex) {
        log.warn("Base de conhecimento indisponível: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "A base de conhecimento está indisponível no momento. Tente novamente em instantes.");
    }

    @ExceptionHandler(KnowledgeBaseIngestor.IngestionInProgressException.class)
    ProblemDetail handleIngestionRunning(KnowledgeBaseIngestor.IngestionInProgressException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler({TimeoutException.class, AsyncRequestTimeoutException.class})
    ProblemDetail handleTimeout(Exception ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                "A resposta demorou mais que o limite. Tente novamente.");
    }
}
