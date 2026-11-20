package com.helpdesk.api.demo;

import com.helpdesk.api.config.AppProperties;
import com.helpdesk.api.shared.ApiException;
import com.helpdesk.api.shared.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Escenarios de demostración y de carga (solo si DEMO_ENABLED=true y solo ADMIN). */
@RestController
@RequestMapping("/api/v1/demo")
@PreAuthorize("hasRole('ADMIN')")
public class DemoController {

    private static final LocalDate MINIMO = LocalDate.of(2015, 1, 1);
    private final DemoService demo;
    private final AppProperties props;

    public DemoController(DemoService demo, AppProperties props) {
        this.demo = demo;
        this.props = props;
    }

    /**
     * Rango: {@code desde} (y opcionalmente {@code hasta}) o, si no se envía, los últimos {@code dias}.
     * Sin usuarios/técnicos/administradores se usan los valores del escenario pequeño (8, 6, 0).
     */
    public record EscenarioRequest(@Min(1) @Max(3650) Integer dias, LocalDate desde, LocalDate hasta,
                                   @Min(1) @Max(50000) int tickets,
                                   @Min(1) @Max(500) Integer usuarios, @Min(1) @Max(100) Integer tecnicos,
                                   @Min(0) @Max(10) Integer administradores, Long semilla) {}

    @PostMapping("/escenario")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> generar(@Valid @RequestBody EscenarioRequest r) {
        if (!props.demoEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_DESACTIVADO", "El modo de demostración está desactivado (DEMO_ENABLED=false)");
        }
        if ("EN_PROCESO".equals(demo.estado().get("estado"))) {
            throw ApiException.conflict("DEMO_EN_PROCESO", "Ya hay un escenario generándose");
        }
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));
        LocalDate hasta = r.hasta() == null || r.hasta().isAfter(hoy) ? hoy : r.hasta();
        LocalDate desde = r.desde() != null ? r.desde() : hasta.minusDays(r.dias() == null ? 60 : r.dias());
        if (desde.isBefore(MINIMO) || !desde.isBefore(hasta)) {
            throw ApiException.badRequest("RANGO_INVALIDO", "La fecha inicial debe ser anterior a la final y posterior a " + MINIMO);
        }
        var p = new DemoService.Parametros(desde, hasta, r.tickets(),
                r.usuarios() == null ? 8 : r.usuarios(), r.tecnicos() == null ? 6 : r.tecnicos(),
                r.administradores() == null ? 0 : r.administradores(), r.semilla() == null ? 42L : r.semilla());
        demo.generar(p, CurrentUser.get().id());
        return Map.of("estado", "EN_PROCESO", "desde", desde.toString(), "hasta", hasta.toString(),
                "mensaje", "El escenario se está generando. Consulta /api/v1/demo/estado.");
    }

    @GetMapping("/estado")
    public Map<String, Object> estado() {
        return Map.of("habilitado", props.demoEnabled(), "ultimo", demo.estado());
    }
}
