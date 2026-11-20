package com.helpdesk.api.ticket;

import com.helpdesk.api.assignment.AssignmentScorer;
import com.helpdesk.api.shared.PageResponse;
import com.helpdesk.api.ticket.TicketDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService service;
    private final AttachmentService attachments;

    public TicketController(TicketService service, AttachmentService attachments) {
        this.service = service;
        this.attachments = attachments;
    }

    /** Creación desde el formulario web: parte "datos" (JSON) + parte "imagenes" (0..5 archivos). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetalle crearConImagenes(@Valid @RequestPart("datos") CrearTicketRequest datos,
                                          @RequestPart(value = "imagenes", required = false) List<MultipartFile> imagenes) {
        Ticket t = service.crear(datos, imagenes, Canal.WEB);
        return service.detalle(t.getId());
    }

    /** Creación sin imágenes (usada por el agente de IA tras la confirmación explícita del usuario). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetalle crear(@Valid @RequestBody CrearTicketRequest datos,
                               @RequestParam(defaultValue = "WEB") Canal canal) {
        Ticket t = service.crear(datos, List.of(), canal == Canal.DEMO ? Canal.WEB : canal);
        return service.detalle(t.getId());
    }

    @GetMapping
    public PageResponse<TicketResumen> listar(@RequestParam(required = false) EstadoTicket estado,
                                              @RequestParam(required = false) Prioridad prioridad,
                                              @RequestParam(required = false) Long categoriaId,
                                              @RequestParam(required = false) Long sedeId,
                                              @RequestParam(required = false) UUID tecnicoId,
                                              @RequestParam(required = false) Boolean requiereRevision,
                                              @RequestParam(required = false) Boolean sinTecnico,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.listar(new TicketService.Filtros(estado, prioridad, categoriaId, sedeId, tecnicoId,
                requiereRevision, sinTecnico, q), page, size);
    }

    @GetMapping("/{id}")
    public TicketDetalle detalle(@PathVariable UUID id) { return service.detalle(id); }

    @GetMapping("/numero/{numero}")
    public TicketDetalle porNumero(@PathVariable String numero) { return service.detallePorNumero(numero); }

    @PostMapping("/{id}/estado")
    public TicketDetalle estado(@PathVariable UUID id, @Valid @RequestBody CambioEstadoRequest r) {
        return service.cambiarEstado(id, r);
    }

    @PatchMapping("/{id}/prioridad")
    public TicketDetalle prioridad(@PathVariable UUID id, @Valid @RequestBody CambioPrioridadRequest r) {
        return service.cambiarPrioridad(id, r);
    }

    @PatchMapping("/{id}/clasificacion")
    public TicketDetalle clasificacion(@PathVariable UUID id, @Valid @RequestBody ClasificacionRequest r) {
        return service.reclasificar(id, r);
    }

    @PostMapping("/{id}/actividades")
    public TicketDetalle actividad(@PathVariable UUID id, @Valid @RequestBody ActividadRequest r) {
        return service.registrarActividad(id, r);
    }

    @PostMapping("/{id}/diagnosticos/{diagnosticoId}/validacion")
    public TicketDetalle validar(@PathVariable UUID id, @PathVariable UUID diagnosticoId,
                                 @RequestBody ValidacionDiagnosticoRequest r) {
        return service.validarDiagnostico(id, diagnosticoId, r.aceptado());
    }

    @PostMapping("/{id}/encuesta")
    public TicketDetalle encuesta(@PathVariable UUID id, @Valid @RequestBody EncuestaRequest r) {
        return service.calificar(id, r);
    }

    @PostMapping("/{id}/asignacion")
    public TicketDetalle asignar(@PathVariable UUID id, @Valid @RequestBody AsignacionRequest r) {
        return service.asignar(id, r);
    }

    @GetMapping("/{id}/recomendaciones")
    public AssignmentScorer.Result recomendaciones(@PathVariable UUID id) { return service.recomendaciones(id); }

    @PostMapping("/{id}/solicitar-reasignacion")
    public TicketDetalle solicitarReasignacion(@PathVariable UUID id, @Valid @RequestBody MotivoRequest r) {
        return service.solicitarReasignacion(id, r.motivo());
    }

    @PostMapping(value = "/{id}/adjuntos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TicketDetalle adjuntar(@PathVariable UUID id, @RequestPart("imagenes") List<MultipartFile> imagenes) {
        return service.agregarAdjuntos(id, imagenes);
    }

    /** La imagen se sirve a través del backend tras verificar permisos: el almacenamiento S3 nunca se expone. */
    @GetMapping("/{id}/adjuntos/{adjuntoId}/contenido")
    public ResponseEntity<byte[]> contenido(@PathVariable UUID id, @PathVariable UUID adjuntoId) {
        Adjunto a = service.adjuntoAutorizado(id, adjuntoId);
        byte[] bytes = attachments.leer(a).asByteArray();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getMime()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(bytes);
    }
}
