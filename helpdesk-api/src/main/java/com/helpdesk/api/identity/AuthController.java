package com.helpdesk.api.identity;

import com.helpdesk.api.config.AppProperties;
import com.helpdesk.api.shared.ApiException;
import com.helpdesk.api.shared.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String COOKIE = "hd_refresh";
    private final AuthService auth;
    private final UsuarioRepository usuarios;
    private final AppProperties props;

    public AuthController(AuthService auth, UsuarioRepository usuarios, AppProperties props) {
        this.auth = auth;
        this.usuarios = usuarios;
        this.props = props;
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
    public record TokenResponse(String accessToken, long expiresIn, UsuarioDto usuario) {}

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return withCookie(auth.login(req.email(), req.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@CookieValue(name = COOKIE, required = false) String refresh) {
        return withCookie(auth.refresh(refresh));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String refresh) {
        auth.logout(refresh);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @GetMapping("/me")
    public UsuarioDto me() {
        return usuarios.findById(CurrentUser.get().id()).map(UsuarioDto::of)
                .orElseThrow(() -> ApiException.notFound("Usuario"));
    }

    private ResponseEntity<TokenResponse> withCookie(AuthService.Tokens t) {
        ResponseCookie c = cookie(t.refreshToken(), Duration.ofDays(props.jwt().refreshTtlDays()));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, c.toString())
                .body(new TokenResponse(t.accessToken(), t.expiresInSeconds(), UsuarioDto.of(t.usuario())));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
    }
}
