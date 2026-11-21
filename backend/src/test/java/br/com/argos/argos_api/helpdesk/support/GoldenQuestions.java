package br.com.argos.argos_api.helpdesk.support;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Reference questions with the documents that answer each one (several when more than one document legitimately
 * covers the question). Update it when the official documents change.
 */
public final class GoldenQuestions {

    public record Golden(String question, List<String> documents) {
    }

    private GoldenQuestions() {
    }

    public static List<Golden> load() {
        try (InputStream stream = GoldenQuestions.class.getClassLoader().getResourceAsStream("helpdesk/golden-questions.json")) {
            return JsonMapper.builder().build().readValue(stream, new TypeReference<List<Golden>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
