package com.helpdesk.api.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalogos")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) { this.catalog = catalog; }

    public record SedeRequest(@NotBlank String codigo, @NotBlank String nombre, @NotBlank String ciudad) {}
    public record EquipoRequest(@NotBlank String codigo, @NotBlank String tipo, String marca, String modelo,
                                @NotNull Long sedeId) {}

    @GetMapping
    public CatalogService.CatalogoDto catalogos() { return catalog.snapshot(); }

    @PostMapping("/sedes")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.Item crearSede(@Valid @RequestBody SedeRequest r) {
        Sede s = catalog.crearSede(r.codigo(), r.nombre(), r.ciudad());
        return new CatalogService.Item(s.getId(), s.getCodigo(), s.getNombre());
    }

    @PostMapping("/equipos")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.EquipoItem crearEquipo(@Valid @RequestBody EquipoRequest r) {
        Equipo e = catalog.crearEquipo(r.codigo(), r.tipo(), r.marca(), r.modelo(), r.sedeId());
        return new CatalogService.EquipoItem(e.getId(), e.getCodigoInventario(), e.getTipo(), e.getMarca(), e.getModelo(), e.getSedeId());
    }
}
