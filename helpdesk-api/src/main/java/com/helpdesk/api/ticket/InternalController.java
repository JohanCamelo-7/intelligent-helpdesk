package com.helpdesk.api.ticket;

import com.helpdesk.api.catalog.CatalogService;
import com.helpdesk.api.config.AppProperties;
import com.helpdesk.api.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Endpoints de servicio a servicio (ai-service). Nginx NO los publica y exigen la clave
 * compartida X-Service-Key. Devuelven solo lo que la IA necesita (sin nombre ni correo).
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final TicketService tickets;
    private final CatalogService catalog;
    private final byte[] serviceKey;

    public InternalController(TicketService tickets, CatalogService catalog, AppProperties props) {
        this.tickets = tickets;
        this.catalog = catalog;
        this.serviceKey = props.serviceKey().getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/catalogos")
    public CatalogService.CatalogoDto catalogos(@RequestHeader(value = "X-Service-Key", required = false) String key) {
        check(key);
        return catalog.snapshot();
    }

    @GetMapping("/tickets/{id}/contexto")
    public TicketDtos.ContextoIa contexto(@RequestHeader(value = "X-Service-Key", required = false) String key,
                                          @PathVariable UUID id) {
        check(key);
        return tickets.contextoIa(id);
    }

    private void check(String key) {
        if (key == null || !MessageDigest.isEqual(serviceKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CLAVE_SERVICIO", "Clave de servicio inválida");
        }
    }
}
