package com.helpdesk.api.ticket;

import com.helpdesk.api.config.AppProperties;
import com.helpdesk.api.shared.ApiException;
import com.helpdesk.api.shared.AuditService;
import com.helpdesk.api.shared.Maps;
import com.helpdesk.api.shared.OutboxService;
import com.helpdesk.api.shared.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Almacena imágenes en SeaweedFS (S3) con nombre aleatorio. En PostgreSQL solo
 * quedan la referencia y los metadatos. El análisis de IA es asíncrono (evento).
 */
@Service
public class AttachmentService {

    private final S3Client s3;
    private final AppProperties props;
    private final AdjuntoRepository adjuntos;
    private final ClamAvClient clamav;
    private final OutboxService outbox;
    private final AuditService audit;
    private final TimeProvider time;

    public AttachmentService(S3Client s3, AppProperties props, AdjuntoRepository adjuntos, ClamAvClient clamav,
                             OutboxService outbox, AuditService audit, TimeProvider time) {
        this.s3 = s3;
        this.props = props;
        this.adjuntos = adjuntos;
        this.clamav = clamav;
        this.outbox = outbox;
        this.audit = audit;
        this.time = time;
    }

    /** Se llama dentro de la transacción del ticket. */
    public List<Adjunto> guardar(Ticket ticket, List<MultipartFile> archivos, UUID usuarioId, boolean duranteCreacion) {
        if (archivos == null || archivos.isEmpty()) return List.of();
        List<MultipartFile> reales = archivos.stream().filter(f -> f != null && !f.isEmpty()).toList();
        long existentes = duranteCreacion ? 0 : adjuntos.countByTicketId(ticket.getId());
        if (existentes + reales.size() > props.attachments().maxFiles()) {
            throw ApiException.badRequest("DEMASIADAS_IMAGENES",
                    "Se permiten como máximo " + props.attachments().maxFiles() + " imágenes por ticket");
        }
        return reales.stream().map(f -> guardarUno(ticket, f, usuarioId, duranteCreacion)).toList();
    }

    private Adjunto guardarUno(Ticket ticket, MultipartFile archivo, UUID usuarioId, boolean duranteCreacion) {
        byte[] data;
        try {
            data = archivo.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("ARCHIVO_ILEGIBLE", "No se pudo leer el archivo");
        }
        FileValidator.Resultado r = FileValidator.validar(data, archivo.getOriginalFilename(),
                props.attachments().maxBytes(), props.attachments().maxPixels());
        if (!clamav.isClean(data)) {
            audit.log(usuarioId, "ARCHIVO_RECHAZADO", "ticket", ticket.getId().toString(), "antivirus");
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ARCHIVO_INFECTADO",
                    "El archivo fue rechazado por el antivirus");
        }
        UUID id = UUID.randomUUID();
        String key = "tickets/" + ticket.getId() + "/" + id + "." + r.extension();
        s3.putObject(PutObjectRequest.builder()
                        .bucket(props.s3().attachmentsBucket()).key(key).contentType(r.mime()).build(),
                RequestBody.fromBytes(data));

        Adjunto a = new Adjunto();
        a.setId(id);
        a.setTicketId(ticket.getId());
        a.setObjectKey(key);
        a.setNombreOriginal(nombreSeguro(archivo.getOriginalFilename()));
        a.setMime(r.mime());
        a.setTamano(data.length);
        a.setSha256(sha256(data));
        a.setAncho(r.ancho());
        a.setAlto(r.alto());
        a.setSubidoPor(usuarioId);
        a.setFecha(time.now());
        a.setUpdatedAt(time.now());
        Adjunto saved = adjuntos.save(a);

        outbox.record("Ticket", ticket.getId(), "AttachmentUploaded", Maps.of(
                "adjunto_id", id.toString(),
                "ticket_id", ticket.getId().toString(),
                "mime", r.mime(),
                "tamano", data.length,
                "sha256", saved.getSha256(),
                "object_key", key,
                "durante_creacion", duranteCreacion,
                "es_demo", ticket.isEsDemo(),
                "subido_en", saved.getFecha().toString()));
        return saved;
    }

    public ResponseBytes<GetObjectResponse> leer(Adjunto a) {
        return s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(props.s3().attachmentsBucket()).key(a.getObjectKey()).build());
    }

    static String nombreSeguro(String nombre) {
        if (nombre == null || nombre.isBlank()) return "imagen";
        String n = nombre.replaceAll("[\\\\/]", "_").replaceAll("[^\\p{L}\\p{N}._ -]", "");
        return n.length() > 150 ? n.substring(0, 150) : n;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
