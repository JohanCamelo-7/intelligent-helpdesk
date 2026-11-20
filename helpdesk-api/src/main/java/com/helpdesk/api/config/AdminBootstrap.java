package com.helpdesk.api.config;

import com.helpdesk.api.identity.Rol;
import com.helpdesk.api.identity.Usuario;
import com.helpdesk.api.identity.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Crea el administrador inicial (ADMIN_EMAIL / ADMIN_PASSWORD) si aún no existe ninguno. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public AdminBootstrap(UsuarioRepository usuarios, PasswordEncoder encoder, AppProperties props) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (usuarios.existsByRol(Rol.ADMIN)) return;
        Usuario admin = new Usuario();
        admin.setEmail(props.admin().email().toLowerCase());
        admin.setPasswordHash(encoder.encode(props.admin().password()));
        admin.setNombre("Administrador");
        admin.setCargo("Administrador de la plataforma");
        admin.setArea("Tecnología");
        admin.setRol(Rol.ADMIN);
        usuarios.save(admin);
        log.info("Administrador inicial creado: {}", admin.getEmail());
    }
}
