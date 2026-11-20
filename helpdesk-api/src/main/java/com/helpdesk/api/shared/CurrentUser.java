package com.helpdesk.api.shared;

import com.helpdesk.api.identity.Rol;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.UUID;

/** Usuario autenticado de la petición actual, leído del JWT. */
public record CurrentUser(UUID id, Rol rol, String nombre, String email) {

    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO", "Debes iniciar sesión");
        }
        Jwt jwt = token.getToken();
        List<String> roles = jwt.getClaimAsStringList("roles");
        Rol rol = roles == null || roles.isEmpty() ? Rol.USER : Rol.valueOf(roles.get(0));
        return new CurrentUser(UUID.fromString(jwt.getSubject()), rol,
                jwt.getClaimAsString("nombre"), jwt.getClaimAsString("email"));
    }

    public boolean isAdmin() { return rol == Rol.ADMIN; }
    public boolean isTecnico() { return rol == Rol.TECNICO; }
}
