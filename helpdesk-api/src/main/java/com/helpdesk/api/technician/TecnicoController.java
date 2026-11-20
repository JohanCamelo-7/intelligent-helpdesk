package com.helpdesk.api.technician;

import com.helpdesk.api.shared.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tecnicos")
public class TecnicoController {

    private final TecnicoService service;

    public TecnicoController(TecnicoService service) { this.service = service; }

    public record CrearTecnicoRequest(@NotBlank @Email String email, @NotBlank String password, @NotBlank String nombre,
                                      @NotBlank String cargo, Long sedeId, @NotNull Nivel nivel,
                                      @Min(1) int cargaMaxima, @NotEmpty Set<Long> especialidadIds) {}

    public record ActualizarTecnicoRequest(Nivel nivel, Integer cargaMaxima, Boolean disponible, Set<Long> especialidadIds) {}

    public record DisponibilidadRequest(@NotNull Boolean disponible) {}

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<TecnicoService.TecnicoDto> listar() { return service.listar(); }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public TecnicoService.TecnicoDto crear(@Valid @RequestBody CrearTecnicoRequest r) {
        return service.crear(r.email(), r.password(), r.nombre(), r.cargo(), r.sedeId(), r.nivel(),
                r.cargaMaxima(), r.especialidadIds());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public TecnicoService.TecnicoDto actualizar(@PathVariable UUID id, @RequestBody ActualizarTecnicoRequest r) {
        return service.actualizar(id, r.nivel(), r.cargaMaxima(), r.disponible(), r.especialidadIds());
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('TECNICO')")
    public TecnicoService.TecnicoDto yo() { return service.obtener(CurrentUser.get().id()); }

    @PatchMapping("/me/disponibilidad")
    @PreAuthorize("hasRole('TECNICO')")
    public TecnicoService.TecnicoDto disponibilidad(@Valid @RequestBody DisponibilidadRequest r) {
        return service.actualizar(CurrentUser.get().id(), null, null, r.disponible(), null);
    }
}
