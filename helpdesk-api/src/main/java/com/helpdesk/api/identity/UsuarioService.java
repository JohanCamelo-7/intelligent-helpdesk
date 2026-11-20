package com.helpdesk.api.identity;

import com.helpdesk.api.catalog.SedeRepository;
import com.helpdesk.api.shared.ApiException;
import com.helpdesk.api.shared.AuditService;
import com.helpdesk.api.shared.CurrentUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarios;
    private final SedeRepository sedes;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UsuarioService(UsuarioRepository usuarios, SedeRepository sedes, PasswordEncoder encoder, AuditService audit) {
        this.usuarios = usuarios;
        this.sedes = sedes;
        this.encoder = encoder;
        this.audit = audit;
    }

    public record CrearUsuario(String email, String password, String nombre, String cargo, String area,
                               Long sedeId, Rol rol) {}

    @Transactional
    public Usuario crear(CrearUsuario req) {
        if (usuarios.existsByEmailIgnoreCase(req.email())) {
            throw ApiException.conflict("CORREO_EXISTE", "Ya existe un usuario con ese correo");
        }
        validarPassword(req.password());
        if (req.sedeId() != null && !sedes.existsById(req.sedeId())) throw ApiException.notFound("Sede");
        Usuario u = new Usuario();
        u.setEmail(req.email().trim().toLowerCase());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setNombre(req.nombre().trim());
        u.setCargo(req.cargo().trim());
        u.setArea(req.area());
        u.setSedeId(req.sedeId());
        u.setRol(req.rol());
        Usuario saved = usuarios.save(u);
        audit.log(actorId(), "USUARIO_CREADO", "usuario", saved.getId().toString(), saved.getRol().name());
        return saved;
    }

    @Transactional
    public Usuario actualizar(UUID id, String nombre, String cargo, String area, Long sedeId, Boolean activo,
                              String nuevaPassword) {
        Usuario u = usuarios.findById(id).orElseThrow(() -> ApiException.notFound("Usuario"));
        if (nombre != null) u.setNombre(nombre.trim());
        if (cargo != null) u.setCargo(cargo.trim());
        if (area != null) u.setArea(area);
        if (sedeId != null) {
            if (!sedes.existsById(sedeId)) throw ApiException.notFound("Sede");
            u.setSedeId(sedeId);
        }
        if (activo != null) u.setActivo(activo);
        if (nuevaPassword != null && !nuevaPassword.isBlank()) {
            validarPassword(nuevaPassword);
            u.setPasswordHash(encoder.encode(nuevaPassword));
        }
        audit.log(actorId(), "USUARIO_ACTUALIZADO", "usuario", id.toString(), null);
        return u;
    }

    static void validarPassword(String p) {
        if (p == null || p.length() < 8 || !p.matches(".*[A-Za-z].*") || !p.matches(".*\\d.*")) {
            throw ApiException.badRequest("PASSWORD_DEBIL", "La contraseña debe tener al menos 8 caracteres, letras y números");
        }
    }

    private UUID actorId() {
        try { return CurrentUser.get().id(); } catch (ApiException e) { return null; }
    }
}
