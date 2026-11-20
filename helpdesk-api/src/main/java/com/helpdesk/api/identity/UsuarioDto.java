package com.helpdesk.api.identity;

import java.util.UUID;

public record UsuarioDto(UUID id, String email, String nombre, String cargo, String area,
                         Long sedeId, Rol rol, boolean activo) {
    public static UsuarioDto of(Usuario u) {
        return new UsuarioDto(u.getId(), u.getEmail(), u.getNombre(), u.getCargo(), u.getArea(),
                u.getSedeId(), u.getRol(), u.isActivo());
    }
}
