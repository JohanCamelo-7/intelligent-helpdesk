package com.helpdesk.api.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/usuarios")
@PreAuthorize("hasRole('ADMIN')")
public class UsuarioController {

    private final UsuarioRepository usuarios;
    private final UsuarioService service;

    public UsuarioController(UsuarioRepository usuarios, UsuarioService service) {
        this.usuarios = usuarios;
        this.service = service;
    }

    public record CrearUsuarioRequest(@NotBlank @Email String email, @NotBlank @Size(min = 8) String password,
                                      @NotBlank String nombre, @NotBlank String cargo, String area,
                                      Long sedeId, @NotNull Rol rol) {}

    public record ActualizarUsuarioRequest(String nombre, String cargo, String area, Long sedeId,
                                           Boolean activo, String nuevaPassword) {}

    @GetMapping
    public List<UsuarioDto> listar() {
        return usuarios.findAllByOrderByNombreAsc().stream().map(UsuarioDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioDto crear(@Valid @RequestBody CrearUsuarioRequest r) {
        return UsuarioDto.of(service.crear(new UsuarioService.CrearUsuario(
                r.email(), r.password(), r.nombre(), r.cargo(), r.area(), r.sedeId(), r.rol())));
    }

    @PutMapping("/{id}")
    public UsuarioDto actualizar(@PathVariable UUID id, @RequestBody ActualizarUsuarioRequest r) {
        return UsuarioDto.of(service.actualizar(id, r.nombre(), r.cargo(), r.area(), r.sedeId(),
                r.activo(), r.nuevaPassword()));
    }
}
