package com.helpdesk.api.shared;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/** Serialización JSON (Jackson 3) para eventos, explicaciones y columnas de texto JSON. */
@Component
public class Json {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    public Map<String, Object> readMap(String json) {
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    public List<Map<String, Object>> readList(String json) {
        return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
    }
}
