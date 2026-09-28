package br.com.argos.argos_api.alerting.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, String>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, String> map) {
        return JSON.writeValueAsString(map == null ? Map.of() : map);
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String json) {
        return json == null || json.isBlank() ? new LinkedHashMap<>() : JSON.readValue(json, MAP_TYPE);
    }
}
