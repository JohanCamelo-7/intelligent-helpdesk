package com.helpdesk.api.assignment;

import com.helpdesk.api.catalog.CatalogService;
import com.helpdesk.api.catalog.Categoria;
import com.helpdesk.api.catalog.Especialidad;
import com.helpdesk.api.identity.Usuario;
import com.helpdesk.api.identity.UsuarioRepository;
import com.helpdesk.api.notification.NotificacionService;
import com.helpdesk.api.shared.ApiException;
import com.helpdesk.api.shared.Json;
import com.helpdesk.api.shared.Maps;
import com.helpdesk.api.shared.OutboxService;
import com.helpdesk.api.shared.TimeProvider;
import com.helpdesk.api.technician.Tecnico;
import com.helpdesk.api.technician.TecnicoRepository;
import com.helpdesk.api.technician.TecnicoService;
import com.helpdesk.api.ticket.Ticket;
import com.helpdesk.api.ticket.TicketHistoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AssignmentService {

    private final TecnicoRepository tecnicos;
    private final TecnicoService tecnicoService;
    private final UsuarioRepository usuarios;
    private final CatalogService catalog;
    private final AssignmentConfig config;
    private final PerformanceProvider performance;
    private final AsignacionRepository asignaciones;
    private final TicketHistoryService historial;
    private final NotificacionService notificaciones;
    private final OutboxService outbox;
    private final Json json;
    private final TimeProvider time;

    public AssignmentService(TecnicoRepository tecnicos, TecnicoService tecnicoService, UsuarioRepository usuarios,
                             CatalogService catalog, AssignmentConfig config, PerformanceProvider performance,
                             AsignacionRepository asignaciones, TicketHistoryService historial,
                             NotificacionService notificaciones, OutboxService outbox, Json json, TimeProvider time) {
        this.tecnicos = tecnicos;
        this.tecnicoService = tecnicoService;
        this.usuarios = usuarios;
        this.catalog = catalog;
        this.config = config;
        this.performance = performance;
        this.asignaciones = asignaciones;
        this.historial = historial;
        this.notificaciones = notificaciones;
        this.outbox = outbox;
        this.json = json;
        this.time = time;
    }

    /** Evalúa candidatos para el ticket (no modifica nada). */
    public AssignmentScorer.Result evaluar(Ticket t, double confianza) {
        AssignmentConfig.Valores cfg = config.leer();
        if (t.getCategoriaId() == null) {
            return new AssignmentScorer.Result(List.of(), List.of(), AssignmentScorer.Decision.REVISION,
                    "El ticket no tiene categoría: requiere clasificación manual", cfg.pesos());
        }
        String categoriaCodigo = catalog.categoria(t.getCategoriaId()).map(Categoria::getCodigo).orElse(null);
        Map<Long, Especialidad> esp = catalog.especialidadesPorId();
        Map<UUID, TecnicoService.Carga> cargas = tecnicoService.cargas();
        Map<UUID, Double> desempeno = performance.desempeno(categoriaCodigo);
        Map<UUID, Usuario> users = usuarios.findAllById(tecnicos.findAll().stream().map(Tecnico::getUsuarioId).toList())
                .stream().collect(Collectors.toMap(Usuario::getId, Function.identity()));

        List<AssignmentScorer.Candidate> candidatos = tecnicos.findAll().stream().map(tec -> {
            List<Especialidad> mias = tec.getEspecialidadIds().stream().map(esp::get).filter(Objects::nonNull).toList();
            boolean categoria = mias.stream().anyMatch(e -> e.getCategoriaId().equals(t.getCategoriaId()));
            boolean exacta = t.getTipoFallaId() != null && mias.stream().anyMatch(e -> t.getTipoFallaId().equals(e.getTipoFallaId()));
            Usuario u = users.get(tec.getUsuarioId());
            TecnicoService.Carga c = cargas.getOrDefault(tec.getUsuarioId(), new TecnicoService.Carga(0, 0));
            // Si el ticket ya está asignado a este técnico, su propia carga no debe contarlo dos veces.
            int carga = tec.getUsuarioId().equals(t.getTecnicoId()) ? Math.max(0, c.ponderada() - t.getPrioridad().peso()) : c.ponderada();
            return new AssignmentScorer.Candidate(tec.getUsuarioId(), u == null ? "?" : u.getNombre(), tec.getNivel(),
                    exacta, categoria, carga, tec.getCargaMaxima(), desempeno.get(tec.getUsuarioId()),
                    tec.isDisponible(), u != null && u.isActivo());
        }).toList();

        return AssignmentScorer.evaluar(t.getPrioridad(), confianza, candidatos, cfg.pesos(),
                cfg.umbralConfianza(), cfg.umbralPuntaje());
    }

    /** Asignación automática tras el diagnóstico. Si no procede, marca el ticket para revisión. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AssignmentScorer.Result autoAsignar(Ticket t, double confianza) {
        AssignmentScorer.Result r = evaluar(t, confianza);
        AssignmentScorer.Breakdown mejor = r.mejor();
        t.setTecnicoRecomendadoId(mejor == null ? null : mejor.id());
        t.setRecomendacionJson(json.write(Maps.of("decision", r.decision().name(), "motivo", r.motivo(),
                "ranking", r.ranking().stream().limit(3).toList(), "excluidos", r.excluidos(),
                "pesos", r.pesos())));
        if (r.decision() == AssignmentScorer.Decision.AUTO) {
            registrar(t, mejor.id(), mejor, "AUTO", r.motivo(), null, r.pesos().version());
            t.setRequiereRevision(false);
        } else {
            t.setRequiereRevision(true);
            historial.registrar(t.getId(), "REVISION_REQUERIDA", null, null, r.motivo(), null);
            notificaciones.notificarAdmins("Ticket " + t.getNumero() + " requiere revisión", r.motivo(), t.getId());
        }
        return r;
    }

    /** Asignación o reasignación manual por un ADMIN. Exige motivo. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Asignacion asignarManual(Ticket t, UUID tecnicoId, String motivo, UUID adminId) {
        if (motivo == null || motivo.trim().length() < 5) {
            throw ApiException.badRequest("MOTIVO_REQUERIDO", "Indica el motivo de la asignación manual");
        }
        if (!t.getEstado().abierto()) throw ApiException.conflict("TICKET_CERRADO", "No se puede asignar un ticket cerrado");
        Tecnico tec = tecnicos.findById(tecnicoId).orElseThrow(() -> ApiException.notFound("Técnico"));
        Usuario u = usuarios.findById(tec.getUsuarioId()).orElseThrow();
        if (!u.isActivo()) throw ApiException.badRequest("TECNICO_INACTIVO", "El técnico está inactivo");
        if (tecnicoId.equals(t.getTecnicoId())) throw ApiException.conflict("MISMO_TECNICO", "El ticket ya está asignado a ese técnico");

        AssignmentScorer.Result r = evaluar(t, 1.0);
        AssignmentScorer.Breakdown elegido = r.ranking().stream().filter(b -> b.id().equals(tecnicoId)).findFirst().orElse(null);
        Asignacion a = registrar(t, tecnicoId, elegido, "MANUAL", motivo.trim(), adminId, r.pesos().version());
        t.setRequiereRevision(false);
        return a;
    }

    private Asignacion registrar(Ticket t, UUID tecnicoId, AssignmentScorer.Breakdown asignado, String modo,
                                 String motivo, UUID actor, String pesosVersion) {
        UUID anterior = t.getTecnicoId();
        asignaciones.findByTicketIdAndActivaTrue(t.getId()).forEach(a -> { a.setActiva(false); a.setUpdatedAt(time.now()); });

        UUID recomendado = t.getTecnicoRecomendadoId();
        Double puntajeRecomendado = null;
        if (t.getRecomendacionJson() != null) {
            Object rank = json.readMap(t.getRecomendacionJson()).get("ranking");
            if (rank instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m && m.get("puntaje") instanceof Number n) {
                puntajeRecomendado = n.doubleValue();
            }
        }
        Asignacion a = new Asignacion();
        a.setTicketId(t.getId());
        a.setTecnicoRecomendadoId(recomendado);
        a.setTecnicoAsignadoId(tecnicoId);
        a.setModo(modo);
        a.setPuntajeRecomendado(bd(puntajeRecomendado));
        a.setPuntajeAsignado(bd(asignado == null ? null : asignado.puntaje()));
        a.setMotivo(motivo.length() > 300 ? motivo.substring(0, 300) : motivo);
        a.setExplicacion(asignado == null ? null : json.write(asignado));
        a.setPesosVersion(pesosVersion);
        a.setAsignadoPor(actor);
        a.setFecha(time.now());
        a.setUpdatedAt(time.now());
        Asignacion saved = asignaciones.save(a);

        t.setTecnicoId(tecnicoId);
        if (t.getFechaAsignacion() == null) t.setFechaAsignacion(time.now());
        historial.registrar(t.getId(), anterior == null ? "ASIGNACION" : "REASIGNACION",
                anterior, tecnicoId, modo + ": " + motivo, actor);

        outbox.record("Ticket", t.getId(), "TechnicianAssigned", Maps.of(
                "asignacion_id", saved.getId().toString(),
                "ticket_id", t.getId().toString(),
                "modo", modo,
                "tecnico_recomendado_id", recomendado == null ? null : recomendado.toString(),
                "tecnico_asignado_id", tecnicoId.toString(),
                "tecnico_anterior_id", anterior == null ? null : anterior.toString(),
                "puntaje_recomendado", puntajeRecomendado,
                "puntaje_asignado", asignado == null ? null : asignado.puntaje(),
                "motivo", saved.getMotivo(),
                "pesos_version", pesosVersion,
                "es_demo", t.isEsDemo(),
                "asignado_en", saved.getFecha().toString()));

        notificaciones.notificar(tecnicoId, "Nuevo ticket asignado: " + t.getNumero(),
                "Se te asignó el ticket " + t.getNumero() + " (" + t.getPrioridad() + ").", t.getId());
        notificaciones.notificar(t.getSolicitanteId(), "Tu ticket " + t.getNumero() + " tiene técnico",
                "Un técnico fue asignado a tu solicitud y la atenderá pronto.", t.getId());
        if (anterior != null) {
            notificaciones.notificar(anterior, "Ticket reasignado: " + t.getNumero(),
                    "El ticket fue reasignado a otro técnico.", t.getId());
        }
        return saved;
    }

    private static BigDecimal bd(Double v) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
