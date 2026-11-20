package com.helpdesk.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Configuración propia de la aplicación (prefijo "helpdesk" en application.yml). */
@ConfigurationProperties(prefix = "helpdesk")
public record AppProperties(
        Jwt jwt,
        boolean cookieSecure,
        List<String> corsOrigins,
        String serviceKey,
        Admin admin,
        S3 s3,
        Attachments attachments,
        ClamAv clamav,
        Ai ai,
        Trino trino,
        Airflow airflow,
        boolean demoEnabled
) {
    public record Jwt(String secret, int accessTtlMinutes, int refreshTtlDays) {}
    public record Admin(String email, String password) {}
    public record S3(String endpoint, String region, String accessKey, String secretKey,
                     String attachmentsBucket, String reportsBucket) {}
    public record Attachments(int maxFiles, long maxBytes, long maxPixels) {}
    public record ClamAv(boolean enabled, String host, int port) {}
    public record Ai(double confidenceThreshold, double minScore) {}
    public record Trino(String url, String user) {}
    public record Airflow(String url, String user, String password) {}
}
