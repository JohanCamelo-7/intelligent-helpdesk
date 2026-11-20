package com.helpdesk.api.identity;

import com.helpdesk.api.config.AppProperties;
import com.helpdesk.api.config.JwtConfig;
import com.helpdesk.api.shared.ApiException;
import com.helpdesk.api.shared.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {

    private static final int MAX_INTENTOS = 5;
    private static final Duration BLOQUEO = Duration.ofMinutes(15);
    private final UsuarioRepository usuarios;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwtEncoder;
    private final AppProperties props;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(UsuarioRepository usuarios, RefreshTokenRepository refreshTokens, PasswordEncoder encoder,
                       JwtEncoder jwtEncoder, AppProperties props, AuditService audit) {
        this.usuarios = usuarios;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.audit = audit;
        this.dummyHash = encoder.encode("contrasena-ficticia-para-tiempo-constante");
    }

    public record Tokens(String accessToken, long expiresInSeconds, String refreshToken, Usuario usuario) {}

    @Transactional(noRollbackFor = ApiException.class)
    public Tokens login(String email, String password) {
        Usuario u = usuarios.findByEmailIgnoreCase(email.trim()).orElse(null);
        Instant now = Instant.now();
        if (u == null) {
            encoder.matches(password, dummyHash); // mismo costo que un usuario existente
            audit.log(null, "LOGIN_FALLIDO", "usuario", null, "correo inexistente");
            throw credencialesInvalidas();
        }
        if (u.getBloqueadoHasta() != null && u.getBloqueadoHasta().isAfter(now)) {
            throw new ApiException(HttpStatus.LOCKED, "CUENTA_BLOQUEADA",
                    "Cuenta bloqueada temporalmente por intentos fallidos. Intenta en unos minutos.");
        }
        if (!u.isActivo() || !encoder.matches(password, u.getPasswordHash())) {
            u.setIntentosFallidos(u.getIntentosFallidos() + 1);
            if (u.getIntentosFallidos() >= MAX_INTENTOS) {
                u.setBloqueadoHasta(now.plus(BLOQUEO));
                u.setIntentosFallidos(0);
            }
            audit.log(u.getId(), "LOGIN_FALLIDO", "usuario", u.getId().toString(), null);
            throw credencialesInvalidas();
        }
        u.setIntentosFallidos(0);
        u.setBloqueadoHasta(null);
        audit.log(u.getId(), "LOGIN", "usuario", u.getId().toString(), null);
        return issue(u);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Tokens refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) throw sesionExpirada();
        RefreshToken rt = refreshTokens.findByTokenHash(sha256(rawRefreshToken)).orElseThrow(this::sesionExpirada);
        if (rt.isRevocado()) {
            // Reutilización de un token ya rotado: posible robo. Se revocan todos.
            refreshTokens.revokeAll(rt.getUsuarioId());
            throw sesionExpirada();
        }
        if (rt.getExpiraEn().isBefore(Instant.now())) throw sesionExpirada();
        rt.setRevocado(true);
        Usuario u = usuarios.findById(rt.getUsuarioId()).filter(Usuario::isActivo).orElseThrow(this::sesionExpirada);
        return issue(u);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null) return;
        refreshTokens.findByTokenHash(sha256(rawRefreshToken)).ifPresent(rt -> rt.setRevocado(true));
    }

    public String accessTokenFor(Usuario u) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(u.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(props.jwt().accessTtlMinutes())))
                .claim("roles", List.of(u.getRol().name()))
                .claim("nombre", u.getNombre())
                .claim("email", u.getEmail())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private Tokens issue(Usuario u) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken rt = new RefreshToken();
        rt.setId(UUID.randomUUID());
        rt.setUsuarioId(u.getId());
        rt.setTokenHash(sha256(raw));
        rt.setCreadoEn(Instant.now());
        rt.setExpiraEn(Instant.now().plus(Duration.ofDays(props.jwt().refreshTtlDays())));
        refreshTokens.save(rt);
        return new Tokens(accessTokenFor(u), props.jwt().accessTtlMinutes() * 60L, raw, u);
    }

    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ApiException credencialesInvalidas() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "CREDENCIALES", "Correo o contraseña incorrectos");
    }

    private ApiException sesionExpirada() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESION_EXPIRADA", "La sesión expiró, inicia sesión de nuevo");
    }
}
