package com.helpdesk.api.ticket;

import com.helpdesk.api.assignment.Asignacion;
import com.helpdesk.api.assignment.AsignacionRepository;
import com.helpdesk.api.assignment.AssignmentScorer;
import com.helpdesk.api.assignment.AssignmentService;
import com.helpdesk.api.catalog.CatalogService;
import com.helpdesk.api.catalog.Categoria;
import com.helpdesk.api.catalog.Equipo;
import com.helpdesk.api.catalog.Sede;
import com.helpdesk.api.catalog.TipoFalla;
import com.helpdesk.api.identity.Rol;
import com.helpdesk.api.identity.Usuario;
import com.helpdesk.api.identity.UsuarioRepository;
import com.helpdesk.api.notification.NotificacionService;
import com.helpdesk.api.shared.ApiException;
import com.helpdesk.api.shared.CurrentUser;
import com.helpdesk.api.shared.Json;
import com.helpdesk.api.shared.Maps;
import com.helpdesk.api.shared.OutboxService;
import com.helpdesk.api.shared.PageResponse;
import com.helpdesk.api.shared.TimeProvider;
import com.helpdesk.api.ticket.TicketDtos.*;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TicketService {

    private static final Set<String> TIPOS_ACTIVIDAD = Set.of("DIAGNOSTICO", "REPARACION", "OBSERVACION", "SEGUIMIENTO", "SOLUCION");

    private final TicketRepository tickets;
    private final UsuarioRepository usuarios;
    private final CatalogService catalog;
    private final AttachmentService attachments;
    private final AdjuntoRepository adjuntos;
    private final DiagnosticoIaRepository diagnosticos;
    private final ActividadRepository actividades;
    private final EncuestaRepository encuestas;
    private final TicketHistorialRepository historialRepo;
    private final AsignacionRepository asignaciones;
    private final TicketHistoryService historial;
    private final AssignmentService assignment;
    private final NotificacionService notificaciones;
    private final OutboxService outbox;
    private final Json json;
    private final TimeProvider time;

    public TicketService(TicketRepository tickets, UsuarioRepository usuarios, CatalogService catalog,
                         AttachmentService attachments, AdjuntoRepository adjuntos, DiagnosticoIaRepository diagnosticos,
                         ActividadRepository actividades, EncuestaRepository encuestas,
                         TicketHistorialRepository historialRepo, AsignacionRepository asignaciones,
                         TicketHistoryService historial, AssignmentService assignment,
                         NotificacionService notificaciones, OutboxService outbox, Json json, TimeProvider time) {
        this.tickets = tickets;
        this.usuarios = usuarios;
        this.catalog = catalog;
        this.attachments = attachments;
        this.adjuntos = adjuntos;
        this.diagnosticos = diagnosticos;
        this.actividades = actividades;
        this.encuestas = encuestas;
        this.historialRepo = historialRepo;
        this.asignaciones = asignaciones;
        this.historial = historial;
        this.assignment = assignment;
        this.notificaciones = notificaciones;
        this.outbox = outbox;
        this.json = json;
        this.time = time;
    }

    // =====================================================================
    // Creación
    // =====================================================================

    @Transactional
    public Ticket crear(CrearTicketRequest req, List<MultipartFile> imagenes, Canal canal) {
        Usuario solicitante = usuarios.findById(CurrentUser.get().id()).orElseThrow(() -> ApiException.notFound("Usuario"));
        return crearComo(solicitante, req, imagenes, canal, false);
    }

    @Transactional
    public Ticket crearComo(Usuario solicitante, CrearTicketRequest req, List<MultipartFile> imagenes,
                            Canal canal, boolean esDemo) {
        Sede sede = catalog.sede(req.sedeId());
        Equipo equipo = null;
        if (req.equipoId() != null) {
            equipo = catalog.equipo(req.equipoId()).orElseThrow(() -> ApiException.notFound("Equipo"));
            if (!equipo.getSedeId().equals(sede.getId())) {
                throw ApiException.badRequest("EQUIPO_OTRA_SEDE", "El equipo seleccionado pertenece a otra sede");
            }
        }
        Instant now = time.now();
        Ticket t = new Ticket();
        t.setNumero(String.format("SOP-%06d", tickets.nextNumero()));
        t.setSolicitanteId(solicitante.getId());
        t.setCargoSolicitante(solicitante.getCargo());
        t.setCorreoSolicitante(solicitante.getEmail());
        t.setSedeId(sede.getId());
        t.setEquipoId(equipo == null ? null : equipo.getId());
        t.setDescripcion(req.descripcion().trim());
        t.setImpacto(req.impacto());
        t.setBloqueaTrabajo(req.bloqueaTrabajo());
        t.setCanal(canal);
        t.setPrioridad(PriorityRules.estimar(req.impacto(), req.bloqueaTrabajo(), null));
        t.setEstado(EstadoTicket.PENDIENTE);
        t.setEsDemo(esDemo);
        t.setFechaCreacion(now);
        tickets.save(t);

        historial.registrar(t.getId(), "CREACION", null, EstadoTicket.PENDIENTE, "Canal " + canal, solicitante.getId());
        List<Adjunto> guardados = attachments.guardar(t, imagenes, solicitante.getId(), true);

        outbox.record("Ticket", t.getId(), "TicketCreated", Maps.of(
                "ticket_id", t.getId().toString(),
                "numero", t.getNumero(),
                "solicitante_id", solicitante.getId().toString(),
                "cargo", solicitante.getCargo(),
                "area", solicitante.getArea(),
                "sede_codigo", sede.getCodigo(),
                "equipo_codigo", equipo == null ? null : equipo.getCodigoInventario(),
                "canal", canal.name(),
                "impacto", req.impacto().name(),
                "bloquea_trabajo", req.bloqueaTrabajo(),
                "prioridad_codigo", t.getPrioridad().name(),
                "estado_codigo", t.getEstado().name(),
                "adjuntos", guardados.size(),
                "longitud_descripcion", t.getDescripcion().length(),
                "es_demo", esDemo,
                "creado_en", now.toString()));

        notificaciones.notificar(solicitante.getId(), "Solicitud " + t.getNumero() + " registrada",
                "Estamos analizando tu solicitud para asignar al técnico adecuado.", t.getId());
        return t;
    }

    // =====================================================================
    // Consultas
    // =====================================================================

    public record Filtros(EstadoTicket estado, Prioridad prioridad, Long categoriaId, Long sedeId, UUID tecnicoId,
                          Boolean requiereRevision, Boolean sinTecnico, String texto) {}

    @Transactional(readOnly = true)
    public PageResponse<TicketResumen> listar(Filtros f, int page, int size) {
        CurrentUser me = CurrentUser.get();
        Specification<Ticket> spec = (r, q, cb) -> cb.conjunction();
        if (me.rol() == Rol.USER) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("solicitanteId"), me.id()));
        } else if (me.rol() == Rol.TECNICO) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("tecnicoId"), me.id()));
        } else if (f.tecnicoId() != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("tecnicoId"), f.tecnicoId()));
        }
        if (f.estado() != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("estado"), f.estado()));
        if (f.prioridad() != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("prioridad"), f.prioridad()));
        if (f.categoriaId() != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("categoriaId"), f.categoriaId()));
        if (f.sedeId() != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("sedeId"), f.sedeId()));
        if (Boolean.TRUE.equals(f.requiereRevision())) spec = spec.and((r, q, cb) -> cb.isTrue(r.<Boolean>get("requiereRevision")));
        if (Boolean.TRUE.equals(f.sinTecnico())) spec = spec.and((r, q, cb) -> cb.isNull(r.get("tecnicoId")));
        if (f.texto() != null && !f.texto().isBlank()) {
            String like = "%" + f.texto().trim().toLowerCase().replace("%", "").replace("_", "") + "%";
            spec = spec.and((r, q, cb) -> {
                Predicate desc = cb.like(cb.lower(r.<String>get("descripcion")), like);
                Predicate num = cb.like(cb.lower(r.<String>get("numero")), like);
                return cb.or(desc, num);
            });
        }
        Page<Ticket> p = tickets.findAll(spec, PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "fechaCreacion")));
        Mapper m = mapper(p.getContent());
        return PageResponse.of(p, m::resumen);
    }

    @Transactional(readOnly = true)
    public TicketDetalle detalle(UUID id) {
        Ticket t = cargarConAcceso(id);
        return detalleDe(t);
    }

    @Transactional(readOnly = true)
    public TicketDetalle detallePorNumero(String numero) {
        Ticket t = tickets.findByNumero(numero.toUpperCase()).orElseThrow(() -> ApiException.notFound("Ticket"));
        verificarAcceso(t, CurrentUser.get());
        return detalleDe(t);
    }

    private TicketDetalle detalleDe(Ticket t) {
        CurrentUser me = CurrentUser.get();
        Mapper m = mapper(List.of(t));
        List<TicketHistorial> hist = historialRepo.findByTicketIdOrderByFechaAsc(t.getId());
        List<Asignacion> asigs = asignaciones.findByTicketIdOrderByFechaAsc(t.getId());
        List<Actividad> acts = actividades.findByTicketIdOrderByFechaAsc(t.getId());
        Set<UUID> ids = new HashSet<>();
        hist.forEach(h -> { if (h.getUsuarioId() != null) ids.add(h.getUsuarioId()); });
        asigs.forEach(a -> { ids.add(a.getTecnicoAsignadoId()); if (a.getTecnicoRecomendadoId() != null) ids.add(a.getTecnicoRecomendadoId()); });
        acts.forEach(a -> ids.add(a.getTecnicoId()));
        Map<UUID, String> nombres = usuarios.findAllById(ids).stream().collect(Collectors.toMap(Usuario::getId, Usuario::getNombre));
        Map<String, String> tiposNombre = catalog.tiposPorId().values().stream()
                .collect(Collectors.toMap(TipoFalla::getCodigo, TipoFalla::getNombre));

        boolean admin = me.isAdmin();
        boolean tecnico = me.id().equals(t.getTecnicoId());
        boolean dueno = me.id().equals(t.getSolicitanteId());
        // Los datos del solicitante (correo) solo los ven el ADMIN y el técnico asignado.
        boolean verContacto = admin || tecnico;

        Permisos permisos = new Permisos(
                t.getEstado() == EstadoTicket.PENDIENTE && t.getTecnicoId() != null && (admin || tecnico),
                t.getEstado() == EstadoTicket.EN_CURSO && (admin || tecnico),
                (t.getEstado() == EstadoTicket.PENDIENTE && (admin || dueno)) || (t.getEstado() == EstadoTicket.EN_CURSO && admin),
                t.getEstado() == EstadoTicket.FINALIZADA && (admin || dueno),
                t.getEstado().abierto() && tecnico,
                t.getEstado().abierto() && (admin || tecnico),
                t.getEstado().abierto() && admin,
                t.getEstado().abierto() && admin,
                t.getEstado().abierto() && tecnico,
                t.getEstado().abierto() && (admin || tecnico || dueno),
                t.getEstado() == EstadoTicket.FINALIZADA && dueno && encuestas.findByTicketId(t.getId()).isEmpty());

        Map<String, Object> recomendacion = admin && t.getRecomendacionJson() != null ? json.readMap(t.getRecomendacionJson()) : null;

        return new TicketDetalle(
                m.resumen(t), t.getDescripcion(), t.getImpacto(), t.isBloqueaTrabajo(),
                t.getCargoSolicitante(), verContacto ? t.getCorreoSolicitante() : null,
                m.categoriaCodigo(t), m.tipoCodigo(t), t.getSolucion(), t.getReaperturas(),
                t.getFechaAsignacion(), t.getFechaInicioAtencion(), recomendacion,
                hist.stream().map(h -> new HistorialDto(h.getTipo(), h.getValorAnterior(), h.getValorNuevo(), h.getDetalle(),
                        h.getUsuarioId() == null ? "Sistema" : nombres.getOrDefault(h.getUsuarioId(), "?"), h.getFecha())).toList(),
                adjuntos.findByTicketIdOrderByFechaAsc(t.getId()).stream().map(a -> new AdjuntoDto(a.getId(), a.getNombreOriginal(),
                        a.getMime(), a.getTamano(), a.getAncho(), a.getAlto(), a.getFecha())).toList(),
                diagnosticos.findByTicketIdOrderByFechaAsc(t.getId()).stream().map(d -> new DiagnosticoDto(d.getId(), d.getOrigen(),
                        d.getAdjuntoId(), d.getProveedor(), d.getModelo(), d.getTipoEquipo(), d.getCategoriaCodigo(),
                        d.getTipoFallaCodigo(), d.getTipoFallaCodigo() == null ? null : tiposNombre.get(d.getTipoFallaCodigo()),
                        d.getDescripcion(), d.getCausaProbable(), d.getConfianza(), d.isConcluyente(),
                        d.getEvidencias() == null ? List.of() : List.of(d.getEvidencias().split("\n")),
                        d.getRecomendacion(), d.getEspecialidadSugerida(), d.getEstado(), d.getFecha())).toList(),
                (admin || tecnico) ? asigs.stream().map(a -> new AsignacionDto(a.getId(), a.getModo(),
                        a.getTecnicoRecomendadoId() == null ? null : nombres.get(a.getTecnicoRecomendadoId()),
                        nombres.get(a.getTecnicoAsignadoId()), a.getPuntajeRecomendado(), a.getPuntajeAsignado(), a.getMotivo(),
                        a.getExplicacion() == null ? null : json.readMap(a.getExplicacion()), a.isActiva(), a.getFecha())).toList() : List.of(),
                acts.stream().map(a -> new ActividadDto(a.getId(), nombres.getOrDefault(a.getTecnicoId(), "?"), a.getTipo(),
                        a.getDescripcion(), a.getMinutos(), a.getFecha())).toList(),
                encuestas.findByTicketId(t.getId()).map(e -> new EncuestaDto(e.getCalificacion(), e.getComentario(), e.getFecha())).orElse(null),
                permisos);
    }

    // =====================================================================
    // Cambios de estado, prioridad y clasificación
    // =====================================================================

    @Transactional
    public TicketDetalle cambiarEstado(UUID id, CambioEstadoRequest req) {
        CurrentUser me = CurrentUser.get();
        Ticket t = cargarConAcceso(id);
        cambiarEstadoInterno(t, req.estado(), req.solucion(), req.tipoFallaFinalCodigo(), req.comentario(), me.id(), me.rol());
        return detalleDe(t);
    }

    /** Lógica de transición reutilizada por la API y por el generador de demostración. */
    @Transactional
    public void cambiarEstadoInterno(Ticket t, EstadoTicket hacia, String solucion, String tipoFallaFinalCodigo,
                                     String comentario, UUID actorId, Rol rol) {
        EstadoTicket desde = t.getEstado();
        Long tipoFinalId = null;
        TipoFalla tipoFinal = null;
        if (hacia == EstadoTicket.FINALIZADA && tipoFallaFinalCodigo != null) {
            tipoFinal = catalog.tipoPorCodigo(tipoFallaFinalCodigo).orElseThrow(() -> ApiException.notFound("Tipo de falla"));
            tipoFinalId = tipoFinal.getId();
        }
        TicketStateMachine.validar(desde, hacia,
                new TicketStateMachine.Actor(rol, actorId.equals(t.getSolicitanteId()), actorId.equals(t.getTecnicoId())),
                new TicketStateMachine.Contexto(t.getTecnicoId() != null, t.getFechaFinalizacion(), time.now(), solucion, tipoFinalId));

        Instant now = time.now();
        t.setEstado(hacia);
        switch (hacia) {
            case EN_CURSO -> {
                if (desde == EstadoTicket.FINALIZADA) {
                    t.setReaperturas(t.getReaperturas() + 1);
                    t.setFechaFinalizacion(null);
                }
                if (t.getFechaInicioAtencion() == null) t.setFechaInicioAtencion(now);
            }
            case FINALIZADA -> {
                t.setFechaFinalizacion(now);
                t.setSolucion(solucion.trim());
                t.setTipoFallaId(tipoFinal.getId());
                t.setCategoriaId(tipoFinal.getCategoriaId());
                t.setRequiereRevision(false);
                validarDiagnosticosAlCerrar(t, tipoFinal, actorId);
            }
            default -> { }
        }
        historial.registrar(t.getId(), "ESTADO", desde, hacia, comentario, actorId);
        outbox.record("Ticket", t.getId(), "TicketStatusChanged", Maps.of(
                "ticket_id", t.getId().toString(),
                "estado_anterior", desde.name(),
                "estado_nuevo", hacia.name(),
                "cambiado_por", actorId.toString(),
                "rol_actor", rol.name(),
                "es_demo", t.isEsDemo(),
                "cambiado_en", now.toString()));
        if (hacia == EstadoTicket.FINALIZADA) {
            outbox.record("Ticket", t.getId(), "TicketClosed", Maps.of(
                    "ticket_id", t.getId().toString(),
                    "tecnico_id", t.getTecnicoId() == null ? null : t.getTecnicoId().toString(),
                    "categoria_codigo", catalog.categoria(t.getCategoriaId()).map(Categoria::getCodigo).orElse(null),
                    "tipo_falla_final", tipoFinal.getCodigo(),
                    "ia_categoria_propuesta", t.getIaCategoriaCodigo(),
                    "ia_tipo_falla_propuesto", t.getIaTipoFallaCodigo(),
                    "reaperturas", t.getReaperturas(),
                    "es_demo", t.isEsDemo(),
                    "finalizado_en", now.toString()));
            notificaciones.notificar(t.getSolicitanteId(), "Tu ticket " + t.getNumero() + " fue finalizado",
                    "Revisa la solución y califica la atención.", t.getId());
        } else if (hacia == EstadoTicket.EN_CURSO) {
            notificaciones.notificar(t.getSolicitanteId(), "Ticket " + t.getNumero() + " en atención",
                    desde == EstadoTicket.FINALIZADA ? "El ticket fue reabierto." : "Un técnico comenzó a atender tu solicitud.", t.getId());
            if (desde == EstadoTicket.FINALIZADA && t.getTecnicoId() != null) {
                notificaciones.notificar(t.getTecnicoId(), "Ticket reabierto: " + t.getNumero(), "El solicitante reabrió el ticket.", t.getId());
            }
        } else if (hacia == EstadoTicket.CANCELADA && t.getTecnicoId() != null) {
            notificaciones.notificar(t.getTecnicoId(), "Ticket cancelado: " + t.getNumero(), "Ya no requiere atención.", t.getId());
        }
    }

    /** Al cerrar, cada diagnóstico propuesto queda ACEPTADO o CORREGIDO según el tipo confirmado. */
    private void validarDiagnosticosAlCerrar(Ticket t, TipoFalla tipoFinal, UUID actorId) {
        for (DiagnosticoIa d : diagnosticos.findByTicketIdOrderByFechaAsc(t.getId())) {
            if (!"PROPUESTO".equals(d.getEstado()) || !d.isConcluyente()) continue;
            String resultado = tipoFinal.getCodigo().equals(d.getTipoFallaCodigo()) ? "ACEPTADO" : "CORREGIDO";
            registrarValidacion(t, d, resultado, actorId, tipoFinal.getCodigo());
        }
    }

    private void registrarValidacion(Ticket t, DiagnosticoIa d, String resultado, UUID actorId, String tipoFinal) {
        d.setEstado(resultado);
        d.setUpdatedAt(time.now());
        outbox.record("Ticket", t.getId(), "AiDiagnosisValidated", Maps.of(
                "diagnostico_id", d.getId().toString(),
                "ticket_id", t.getId().toString(),
                "origen", d.getOrigen(),
                "resultado", resultado,
                "tipo_falla_propuesto", d.getTipoFallaCodigo(),
                "tipo_falla_final", tipoFinal,
                "validado_por", actorId.toString(),
                "es_demo", t.isEsDemo(),
                "validado_en", time.now().toString()));
    }

    @Transactional
    public TicketDetalle validarDiagnostico(UUID ticketId, UUID diagnosticoId, boolean aceptado) {
        CurrentUser me = CurrentUser.get();
        Ticket t = cargarConAcceso(ticketId);
        if (!me.isAdmin() && !me.id().equals(t.getTecnicoId())) throw ApiException.forbidden();
        DiagnosticoIa d = diagnosticos.findById(diagnosticoId).filter(x -> x.getTicketId().equals(ticketId))
                .orElseThrow(() -> ApiException.notFound("Diagnóstico"));
        if (!"PROPUESTO".equals(d.getEstado())) throw ApiException.conflict("YA_VALIDADO", "El diagnóstico ya fue validado");
        registrarValidacion(t, d, aceptado ? "ACEPTADO" : "CORREGIDO", me.id(), null);
        historial.registrar(t.getId(), "VALIDACION_IA", null, aceptado ? "ACEPTADO" : "CORREGIDO", d.getOrigen(), me.id());
        return detalleDe(t);
    }

    @Transactional
    public TicketDetalle cambiarPrioridad(UUID id, CambioPrioridadRequest req) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) throw ApiException.forbidden();
        Ticket t = cargarConAcceso(id);
        if (!t.getEstado().abierto()) throw ApiException.conflict("TICKET_CERRADO", "El ticket está cerrado");
        aplicarPrioridad(t, req.prioridad(), "ADMIN", req.motivo(), me.id());
        return detalleDe(t);
    }

    private void aplicarPrioridad(Ticket t, Prioridad nueva, String origen, String motivo, UUID actor) {
        Prioridad anterior = t.getPrioridad();
        if (anterior == nueva) return;
        t.setPrioridad(nueva);
        historial.registrar(t.getId(), "PRIORIDAD", anterior, nueva, motivo, actor);
        outbox.record("Ticket", t.getId(), "TicketPriorityChanged", Maps.of(
                "ticket_id", t.getId().toString(),
                "prioridad_anterior", anterior.name(),
                "prioridad_nueva", nueva.name(),
                "origen", origen,
                "es_demo", t.isEsDemo(),
                "cambiado_en", time.now().toString()));
    }

    @Transactional
    public TicketDetalle reclasificar(UUID id, ClasificacionRequest req) {
        CurrentUser me = CurrentUser.get();
        Ticket t = cargarConAcceso(id);
        if (!me.isAdmin() && !me.id().equals(t.getTecnicoId())) throw ApiException.forbidden();
        Categoria cat = catalog.categoriaPorCodigo(req.categoriaCodigo()).orElseThrow(() -> ApiException.notFound("Categoría"));
        TipoFalla tipo = null;
        if (req.tipoFallaCodigo() != null && !req.tipoFallaCodigo().isBlank()) {
            tipo = catalog.tipoPorCodigo(req.tipoFallaCodigo()).orElseThrow(() -> ApiException.notFound("Tipo de falla"));
            if (!tipo.getCategoriaId().equals(cat.getId())) {
                throw ApiException.badRequest("TIPO_OTRA_CATEGORIA", "El tipo de falla no pertenece a la categoría");
            }
        }
        aplicarClasificacion(t, cat, tipo, "MANUAL", null, me.id());
        return detalleDe(t);
    }

    private void aplicarClasificacion(Ticket t, Categoria cat, TipoFalla tipo, String origen, Double confianza, UUID actor) {
        String anterior = catalog.categoria(t.getCategoriaId()).map(Categoria::getCodigo).orElse(null);
        t.setCategoriaId(cat.getId());
        t.setTipoFallaId(tipo == null ? null : tipo.getId());
        historial.registrar(t.getId(), "CLASIFICACION", anterior, cat.getCodigo() + (tipo == null ? "" : " / " + tipo.getCodigo()),
                "Origen " + origen + (confianza == null ? "" : String.format(" (confianza %.2f)", confianza)), actor);
        outbox.record("Ticket", t.getId(), "TicketClassified", Maps.of(
                "ticket_id", t.getId().toString(),
                "categoria_codigo", cat.getCodigo(),
                "tipo_falla_codigo", tipo == null ? null : tipo.getCodigo(),
                "origen", origen,
                "confianza", confianza,
                "requiere_revision", t.isRequiereRevision(),
                "es_demo", t.isEsDemo(),
                "clasificado_en", time.now().toString()));
        Prioridad porReglas = PriorityRules.estimar(t.getImpacto(), t.isBloqueaTrabajo(), cat.getCodigo());
        if (porReglas.peso() > t.getPrioridad().peso()) {
            aplicarPrioridad(t, porReglas, "REGLAS", "Ajuste por categoría " + cat.getCodigo(), actor);
        }
    }

    // =====================================================================
    // Resultado del análisis de IA (evento AiDiagnosisCompleted)
    // =====================================================================

    @Transactional
    @SuppressWarnings("unchecked")
    public void aplicarResultadoIa(Map<String, Object> payload) {
        UUID ticketId = UUID.fromString(Maps.str(payload, "ticket_id"));
        Optional<Ticket> opt = tickets.findById(ticketId);
        if (opt.isEmpty()) return;
        Ticket t = opt.get();
        String proveedor = Optional.ofNullable(Maps.str(payload, "proveedor")).orElse("desconocido");
        String modelo = Optional.ofNullable(Maps.str(payload, "modelo")).orElse("desconocido");
        Instant now = time.now();

        Object lista = payload.get("diagnosticos");
        if (lista instanceof List<?> diags) {
            for (Object o : diags) {
                if (!(o instanceof Map<?, ?> raw)) continue;
                Map<String, Object> m = (Map<String, Object>) raw;
                DiagnosticoIa d = new DiagnosticoIa();
                d.setTicketId(t.getId());
                d.setOrigen("IMAGEN".equals(Maps.str(m, "origen")) ? "IMAGEN" : "TEXTO");
                String adj = Maps.str(m, "adjunto_id");
                d.setAdjuntoId(adj == null ? null : UUID.fromString(adj));
                d.setProveedor(recortar(proveedor, 40));
                d.setModelo(recortar(modelo, 80));
                d.setTipoEquipo(recortar(Maps.str(m, "tipo_equipo"), 60));
                String cat = catalog.categoriaPorCodigo(Maps.str(m, "categoria_codigo")).map(Categoria::getCodigo).orElse(null);
                String tipo = catalog.tipoPorCodigo(Maps.str(m, "tipo_falla_codigo")).map(TipoFalla::getCodigo).orElse(null);
                d.setCategoriaCodigo(cat);
                d.setTipoFallaCodigo(tipo);
                d.setDescripcion(recortar(Maps.str(m, "descripcion"), 2000));
                d.setCausaProbable(recortar(Maps.str(m, "causa_probable"), 1000));
                d.setConfianza(bd(Maps.dbl(m, "confianza", 0)));
                d.setConcluyente(Maps.bool(m, "concluyente") && cat != null);
                Object ev = m.get("evidencias");
                d.setEvidencias(ev instanceof List<?> l ? l.stream().map(String::valueOf).limit(10).collect(Collectors.joining("\n")) : null);
                d.setRecomendacion(recortar(Maps.str(m, "recomendacion"), 1000));
                d.setEspecialidadSugerida(recortar(Maps.str(m, "especialidad_sugerida"), 60));
                d.setEstado("PROPUESTO");
                d.setLatenciaMs((int) Maps.dbl(m, "latencia_ms", 0));
                d.setFecha(now);
                d.setUpdatedAt(now);
                diagnosticos.save(d);
            }
        }

        String trigger = Maps.str(payload, "trigger");
        Map<String, Object> comb = payload.get("combinado") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        double confianza = Maps.dbl(comb, "confianza", 0);
        Optional<Categoria> cat = catalog.categoriaPorCodigo(Maps.str(comb, "categoria_codigo"));
        Optional<TipoFalla> tipo = catalog.tipoPorCodigo(Maps.str(comb, "tipo_falla_codigo"))
                .filter(tf -> cat.isPresent() && tf.getCategoriaId().equals(cat.get().getId()));

        if ("TICKET_CREATED".equals(trigger) && t.getEstado() == EstadoTicket.PENDIENTE && t.getTecnicoId() == null) {
            t.setAiConfianza(bd(confianza));
            t.setIaCategoriaCodigo(cat.map(Categoria::getCodigo).orElse(null));
            t.setIaTipoFallaCodigo(tipo.map(TipoFalla::getCodigo).orElse(null));
            if (cat.isPresent()) {
                t.setRequiereRevision(confianza < 0.75);
                aplicarClasificacion(t, cat.get(), tipo.orElse(null), "IA", confianza, null);
            }
            AssignmentScorer.Result r = assignment.autoAsignar(t, cat.isPresent() ? confianza : 0);
            if (r.decision() == AssignmentScorer.Decision.REVISION) {
                notificaciones.notificar(t.getSolicitanteId(), "Solicitud " + t.getNumero() + " en revisión",
                        "Un administrador revisará tu solicitud para asignar al técnico adecuado.", t.getId());
            }
        } else if (t.getTecnicoId() != null) {
            notificaciones.notificar(t.getTecnicoId(), "Nuevo análisis en " + t.getNumero(),
                    "La IA analizó una imagen nueva del ticket.", t.getId());
        }
    }

    // =====================================================================
    // Asignación, actividades, adjuntos y encuesta
    // =====================================================================

    @Transactional
    public TicketDetalle asignar(UUID id, AsignacionRequest req) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) throw ApiException.forbidden();
        Ticket t = cargarConAcceso(id);
        assignment.asignarManual(t, req.tecnicoId(), req.motivo(), me.id());
        return detalleDe(t);
    }

    @Transactional(readOnly = true)
    public AssignmentScorer.Result recomendaciones(UUID id) {
        if (!CurrentUser.get().isAdmin()) throw ApiException.forbidden();
        Ticket t = tickets.findById(id).orElseThrow(() -> ApiException.notFound("Ticket"));
        double conf = t.getAiConfianza() == null ? 1.0 : t.getAiConfianza().doubleValue();
        return assignment.evaluar(t, conf);
    }

    @Transactional
    public TicketDetalle solicitarReasignacion(UUID id, String motivo) {
        CurrentUser me = CurrentUser.get();
        Ticket t = cargarConAcceso(id);
        if (!me.id().equals(t.getTecnicoId())) throw ApiException.forbidden();
        if (!t.getEstado().abierto()) throw ApiException.conflict("TICKET_CERRADO", "El ticket está cerrado");
        t.setRequiereRevision(true);
        historial.registrar(t.getId(), "SOLICITUD_REASIGNACION", null, null, motivo, me.id());
        outbox.record("Ticket", t.getId(), "ReassignmentRequested", Maps.of(
                "ticket_id", t.getId().toString(), "tecnico_id", me.id().toString(),
                "es_demo", t.isEsDemo(), "solicitado_en", time.now().toString()));
        notificaciones.notificarAdmins("Reasignación solicitada: " + t.getNumero(), motivo, t.getId());
        return detalleDe(t);
    }

    @Transactional
    public TicketDetalle registrarActividad(UUID id, ActividadRequest req) {
        CurrentUser me = CurrentUser.get();
        Ticket t = cargarConAcceso(id);
        registrarActividadInterna(t, me.id(), req.tipo(), req.descripcion(), req.minutos());
        return detalleDe(t);
    }

    @Transactional
    public void registrarActividadInterna(Ticket t, UUID tecnicoId, String tipo, String descripcion, int minutos) {
        if (!tecnicoId.equals(t.getTecnicoId())) throw ApiException.forbidden();
        if (!t.getEstado().abierto()) throw ApiException.conflict("TICKET_CERRADO", "El ticket está cerrado");
        String tp = tipo.toUpperCase();
        if (!TIPOS_ACTIVIDAD.contains(tp)) throw ApiException.badRequest("TIPO_ACTIVIDAD", "Tipo de actividad no válido");
        Actividad a = new Actividad();
        a.setTicketId(t.getId());
        a.setTecnicoId(tecnicoId);
        a.setTipo(tp);
        a.setDescripcion(descripcion.trim());
        a.setMinutos(minutos);
        a.setFecha(time.now());
        a.setUpdatedAt(time.now());
        Actividad saved = actividades.save(a);
        outbox.record("Ticket", t.getId(), "ActivityLogged", Maps.of(
                "actividad_id", saved.getId().toString(),
                "ticket_id", t.getId().toString(),
                "tecnico_id", tecnicoId.toString(),
                "tipo", tp,
                "minutos", minutos,
                "es_demo", t.isEsDemo(),
                "registrado_en", saved.getFecha().toString()));
    }

    @Transactional
    public TicketDetalle agregarAdjuntos(UUID id, List<MultipartFile> imagenes) {
        CurrentUser me = CurrentUser.get();
        Ticket t = cargarConAcceso(id);
        if (!t.getEstado().abierto()) throw ApiException.conflict("TICKET_CERRADO", "El ticket está cerrado");
        List<Adjunto> nuevos = attachments.guardar(t, imagenes, me.id(), false);
        historial.registrar(t.getId(), "ADJUNTOS", null, nuevos.size(), "Imágenes agregadas", me.id());
        return detalleDe(t);
    }

    @Transactional
    public TicketDetalle calificar(UUID id, EncuestaRequest req) {
        CurrentUser me = CurrentUser.get();
        Ticket t = cargarConAcceso(id);
        calificarInterno(t, me.id(), req.calificacion(), req.comentario());
        return detalleDe(t);
    }

    @Transactional
    public void calificarInterno(Ticket t, UUID usuarioId, int calificacion, String comentario) {
        if (!usuarioId.equals(t.getSolicitanteId())) throw ApiException.forbidden();
        if (t.getEstado() != EstadoTicket.FINALIZADA) throw ApiException.conflict("NO_FINALIZADO", "Solo se califican tickets finalizados");
        if (encuestas.findByTicketId(t.getId()).isPresent()) throw ApiException.conflict("YA_CALIFICADO", "Ya calificaste este ticket");
        Encuesta e = new Encuesta();
        e.setTicketId(t.getId());
        e.setUsuarioId(usuarioId);
        e.setCalificacion((short) calificacion);
        e.setComentario(comentario == null || comentario.isBlank() ? null : comentario.trim());
        e.setFecha(time.now());
        encuestas.save(e);
        outbox.record("Ticket", t.getId(), "SurveySubmitted", Maps.of(
                "ticket_id", t.getId().toString(),
                "calificacion", calificacion,
                "tiene_comentario", e.getComentario() != null,
                "es_demo", t.isEsDemo(),
                "enviado_en", e.getFecha().toString()));
    }

    @Transactional(readOnly = true)
    public Adjunto adjuntoAutorizado(UUID ticketId, UUID adjuntoId) {
        cargarConAcceso(ticketId);
        return adjuntos.findById(adjuntoId).filter(a -> a.getTicketId().equals(ticketId))
                .orElseThrow(() -> ApiException.notFound("Adjunto"));
    }

    @Transactional(readOnly = true)
    public ContextoIa contextoIa(UUID id) {
        Ticket t = tickets.findById(id).orElseThrow(() -> ApiException.notFound("Ticket"));
        Sede s = catalog.sede(t.getSedeId());
        Optional<Equipo> e = catalog.equipo(t.getEquipoId());
        return new ContextoIa(t.getId(), t.getNumero(), t.getDescripcion(), s.getCodigo(),
                e.map(Equipo::getTipo).orElse(null), e.map(Equipo::getMarca).orElse(null), t.isEsDemo(),
                adjuntos.findByTicketIdOrderByFechaAsc(t.getId()).stream()
                        .map(a -> new AdjuntoIa(a.getId(), a.getObjectKey(), a.getMime())).toList());
    }

    // =====================================================================
    // Utilidades
    // =====================================================================

    public Ticket cargarConAcceso(UUID id) {
        Ticket t = tickets.findById(id).orElseThrow(() -> ApiException.notFound("Ticket"));
        verificarAcceso(t, CurrentUser.get());
        return t;
    }

    /** USER: solo sus tickets. TECNICO: solo los asignados. ADMIN: todos. Se valida siempre en el backend. */
    static void verificarAcceso(Ticket t, CurrentUser me) {
        boolean ok = switch (me.rol()) {
            case ADMIN -> true;
            case TECNICO -> me.id().equals(t.getTecnicoId()) || me.id().equals(t.getSolicitanteId());
            case USER -> me.id().equals(t.getSolicitanteId());
        };
        if (!ok) throw ApiException.notFound("Ticket");
    }

    private Mapper mapper(List<Ticket> lista) {
        Set<UUID> ids = new HashSet<>();
        lista.forEach(t -> { ids.add(t.getSolicitanteId()); if (t.getTecnicoId() != null) ids.add(t.getTecnicoId()); });
        Map<UUID, Usuario> users = usuarios.findAllById(ids).stream().collect(Collectors.toMap(Usuario::getId, Function.identity()));
        return new Mapper(users, catalog.sedesPorId(), catalog.equiposPorId(), catalog.categoriasPorId(), catalog.tiposPorId());
    }

    private record Mapper(Map<UUID, Usuario> users, Map<Long, Sede> sedes, Map<Long, Equipo> equipos,
                          Map<Long, Categoria> categorias, Map<Long, TipoFalla> tipos) {
        TicketResumen resumen(Ticket t) {
            Usuario tec = t.getTecnicoId() == null ? null : users.get(t.getTecnicoId());
            Usuario sol = users.get(t.getSolicitanteId());
            Equipo e = t.getEquipoId() == null ? null : equipos.get(t.getEquipoId());
            String desc = t.getDescripcion().length() > 140 ? t.getDescripcion().substring(0, 140) + "…" : t.getDescripcion();
            return new TicketResumen(t.getId(), t.getNumero(), desc, t.getEstado(), t.getPrioridad(),
                    t.getCategoriaId() == null ? null : categorias.get(t.getCategoriaId()).getNombre(),
                    t.getTipoFallaId() == null ? null : tipos.get(t.getTipoFallaId()).getNombre(),
                    sedes.get(t.getSedeId()).getNombre(),
                    e == null ? null : e.getCodigoInventario() + " · " + e.getTipo(),
                    t.getTecnicoId(), tec == null ? null : tec.getNombre(), sol == null ? null : sol.getNombre(),
                    t.isRequiereRevision(), t.getAiConfianza(), t.getCanal(), t.isEsDemo(),
                    t.getFechaCreacion(), t.getFechaFinalizacion());
        }
        String categoriaCodigo(Ticket t) { return t.getCategoriaId() == null ? null : categorias.get(t.getCategoriaId()).getCodigo(); }
        String tipoCodigo(Ticket t) { return t.getTipoFallaId() == null ? null : tipos.get(t.getTipoFallaId()).getCodigo(); }
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(Math.max(0, Math.min(1, v))).setScale(3, RoundingMode.HALF_UP);
    }

    private static String recortar(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
