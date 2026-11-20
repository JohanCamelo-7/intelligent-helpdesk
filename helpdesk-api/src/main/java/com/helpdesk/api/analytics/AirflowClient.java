package com.helpdesk.api.analytics;

import java.net.http.HttpClient;
import java.time.Duration;
import com.helpdesk.api.config.AppProperties;
import com.helpdesk.api.shared.ApiException;
import com.helpdesk.api.shared.Json;
import com.helpdesk.api.shared.Maps;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/** Dispara y consulta el DAG del lakehouse con la API REST v2 de Airflow 3. */
@Component
public class AirflowClient {

    public static final String DAG_ID = "lakehouse_pipeline";
    private final AppProperties.Airflow cfg;
    private final Json json;
    private final RestClient http;

    public AirflowClient(AppProperties props, Json json) {
        this.cfg = props.airflow();
        this.json = json;
        // HTTP/1.1 explícito: el cliente de Java usa HTTP/2 por defecto e intenta "Upgrade: h2c" sobre http://,
        // que el servidor de Airflow (Uvicorn) rechaza con 400 "Invalid HTTP request received".
        HttpClient jdk = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.http = RestClient.builder()
                .baseUrl(cfg.url())
                .requestFactory(new JdkClientHttpRequestFactory(jdk))
                .build();
    }

    private String token() {
        String body = json.write(Map.of("username", cfg.user(), "password", cfg.password()));
        String resp = http.post().uri("/auth/token").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(String.class);
        Object t = json.readMap(resp).get("access_token");
        if (t == null) throw new IllegalStateException("Airflow no devolvió token");
        return t.toString();
    }

    public Map<String, Object> dispararPipeline() {
        try {
            String resp = http.post().uri("/api/v2/dags/{dag}/dagRuns", DAG_ID)
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.write(Maps.of("logical_date", null, "note", "Ejecución manual desde el panel ADMIN")))
                    .retrieve().body(String.class);
            Map<String, Object> m = json.readMap(resp);
            return Maps.of("dag_run_id", m.get("dag_run_id"), "estado", m.get("state"));
        } catch (RestClientException | IllegalStateException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AIRFLOW_NO_DISPONIBLE",
                    "No se pudo ejecutar el pipeline en Airflow: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> ultimasEjecuciones() {
        try {
            String resp = http.get().uri("/api/v2/dags/{dag}/dagRuns?limit=10&order_by=-run_after", DAG_ID)
                    .header("Authorization", "Bearer " + token())
                    .retrieve().body(String.class);
            Object runs = json.readMap(resp).get("dag_runs");
            if (!(runs instanceof List<?> l)) return List.of();
            return l.stream().filter(Map.class::isInstance).map(o -> (Map<?, ?>) o)
                    .map(r -> Maps.of("dag_run_id", r.get("dag_run_id"), "estado", r.get("state"),
                            "inicio", r.get("start_date"), "fin", r.get("end_date"), "tipo", r.get("run_type")))
                    .toList();
        } catch (RuntimeException e) {
            return List.of(Maps.of("error", "Airflow no disponible: " + e.getMessage()));
        }
    }
}
