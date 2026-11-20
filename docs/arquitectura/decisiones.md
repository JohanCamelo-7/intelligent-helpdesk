# Decisiones de arquitectura y diferencias con el diseño de la FASE 1

El diseño de la FASE 1 se mantuvo, con estos ajustes hechos al implementar. Cada uno se justifica a continuación.

| # | Diseño FASE 1 | Implementación | Motivo |
| --- | --- | --- | --- |
| 1 | 5 servicios: gateway, identity, helpdesk, ai, analytics | 1 servicio Java (`helpdesk-api`) con módulos por paquete + `ai-service` + `data-ingestion` | Menos memoria (~2 GB menos) y menos puntos de falla para un equipo universitario. Los módulos (`identity`, `ticket`, `technician`, `assignment`, `notification`, `analytics`) no comparten entidades por fuera de su paquete y se comunican por eventos, así que pueden separarse después. |
| 2 | Spring Cloud Gateway | Nginx como puerta de entrada (enrutamiento, límites de peticiones, cabeceras) | Con un solo servicio Java, un gateway adicional no aporta. Nginx ya era obligatorio para el túnel. |
| 3 | JWT RS256 | JWT HS256 con secreto compartido (`JWT_SECRET`) | Un solo emisor; `ai-service` valida con el mismo secreto. RS256 sería necesario con varios emisores. |
| 4 | URLs prefirmadas para imágenes | El backend sirve la imagen tras validar permisos | SeaweedFS no se publica y una URL prefirmada apuntaría a un host interno. Es igual de seguro y más simple detrás del túnel. |
| 5 | 6 DAGs independientes | 3 DAGs: `lakehouse_pipeline` (horario, encadenado), `lakehouse_reconcile` (diario), `lakehouse_maintenance` (semanal) | Encadenar extract → bronze → silver → gold garantiza el orden y que Gold nunca se publique si falla la calidad. |
| 6 | Silver/Gold por particiones incrementales | Reconstrucción completa en cada ejecución | A la escala del proyecto tarda segundos y es idempotente por construcción. Bronze sí es incremental (anti-join por `event_id`). |
| 7 | Agente guiado por LLM con herramientas | Flujo y herramientas controlados por código; el modelo solo interpreta y clasifica | Garantiza que el agente no invente acciones, que solo recomiende comprobaciones de una lista blanca y que solo cree tickets tras confirmación explícita. |
| 8 | MinIO | SeaweedFS (API S3) | MinIO Community fue archivado (abril de 2026). |

Todo lo demás (Medallion con Iceberg, catálogo JDBC, Trino, outbox, idempotencia, Cloudflare Tunnel con Access) se implementó como se diseñó.
