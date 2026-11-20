package com.helpdesk.api.ticket;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contratos de entrada y salida del módulo de tickets. */
public final class TicketDtos {
    private TicketDtos() {}

    public record CrearTicketRequest(
            @NotBlank @Size(min = 10, max = 4000) String descripcion,
            @NotNull Long sedeId,
            Long equipoId,
            @NotNull Impacto impacto,
            boolean bloqueaTrabajo) {}

    public record CambioEstadoRequest(@NotNull EstadoTicket estado, String solucion, String tipoFallaFinalCodigo,
                                      String comentario) {}

    public record CambioPrioridadRequest(@NotNull Prioridad prioridad, @NotBlank String motivo) {}

    public record ClasificacionRequest(@NotBlank String categoriaCodigo, String tipoFallaCodigo) {}

    public record ActividadRequest(@NotBlank String tipo, @NotBlank @Size(max = 2000) String descripcion,
                                   @Min(0) @Max(1440) int minutos) {}

    public record ValidacionDiagnosticoRequest(boolean aceptado) {}

    public record EncuestaRequest(@Min(1) @Max(5) int calificacion, @Size(max = 1000) String comentario) {}

    public record AsignacionRequest(@NotNull UUID tecnicoId, @NotBlank @Size(min = 5, max = 300) String motivo) {}

    public record MotivoRequest(@NotBlank @Size(min = 5, max = 300) String motivo) {}

    public record TicketResumen(UUID id, String numero, String descripcion, EstadoTicket estado, Prioridad prioridad,
                                String categoria, String tipoFalla, String sede, String equipo,
                                UUID tecnicoId, String tecnicoNombre, String solicitanteNombre,
                                boolean requiereRevision, BigDecimal aiConfianza, Canal canal, boolean esDemo,
                                Instant fechaCreacion, Instant fechaFinalizacion) {}

    public record HistorialDto(String tipo, String valorAnterior, String valorNuevo, String detalle,
                               String usuario, Instant fecha) {}

    public record AdjuntoDto(UUID id, String nombre, String mime, long tamano, Integer ancho, Integer alto, Instant fecha) {}

    public record DiagnosticoDto(UUID id, String origen, UUID adjuntoId, String proveedor, String modelo,
                                 String tipoEquipo, String categoriaCodigo, String tipoFallaCodigo, String tipoFallaNombre,
                                 String descripcion, String causaProbable, BigDecimal confianza, boolean concluyente,
                                 List<String> evidencias, String recomendacion, String especialidadSugerida,
                                 String estado, Instant fecha) {}

    public record AsignacionDto(UUID id, String modo, String tecnicoRecomendado, String tecnicoAsignado,
                                BigDecimal puntajeRecomendado, BigDecimal puntajeAsignado, String motivo,
                                Map<String, Object> explicacion, boolean activa, Instant fecha) {}

    public record ActividadDto(UUID id, String tecnico, String tipo, String descripcion, int minutos, Instant fecha) {}

    public record EncuestaDto(int calificacion, String comentario, Instant fecha) {}

    public record Permisos(boolean iniciar, boolean finalizar, boolean cancelar, boolean reabrir,
                           boolean registrarActividad, boolean reclasificar, boolean cambiarPrioridad,
                           boolean asignar, boolean solicitarReasignacion, boolean adjuntar, boolean calificar) {}

    public record TicketDetalle(TicketResumen resumen, String descripcionCompleta, Impacto impacto, boolean bloqueaTrabajo,
                                String cargoSolicitante, String correoSolicitante, String categoriaCodigo,
                                String tipoFallaCodigo, String solucion, int reaperturas,
                                Instant fechaAsignacion, Instant fechaInicioAtencion,
                                Map<String, Object> recomendacion,
                                List<HistorialDto> historial, List<AdjuntoDto> adjuntos,
                                List<DiagnosticoDto> diagnosticos, List<AsignacionDto> asignaciones,
                                List<ActividadDto> actividades, EncuestaDto encuesta, Permisos permisos) {}

    /** Contexto que el servicio de IA necesita (endpoint interno, sin datos personales). */
    public record ContextoIa(UUID ticketId, String numero, String descripcion, String sedeCodigo, String equipoTipo,
                             String equipoMarca, boolean esDemo, List<AdjuntoIa> adjuntos) {}

    public record AdjuntoIa(UUID id, String objectKey, String mime) {}
}
