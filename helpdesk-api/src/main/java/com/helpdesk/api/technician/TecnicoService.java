package com.helpdesk.api.technician;

import com.helpdesk.api.catalog.CatalogService;
import com.helpdesk.api.catalog.Especialidad;
import com.helpdesk.api.identity.Rol;
import com.helpdesk.api.identity.Usuario;
import com.helpdesk.api.identity.UsuarioRepository;
import com.helpdesk.api.identity.UsuarioService;
import com.helpdesk.api.shared.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TecnicoService {

    private final TecnicoRepository tecnicos;
    private final UsuarioRepository usuarios;
    private final UsuarioService usuarioService;
    private final CatalogService catalog;
    private final JdbcTemplate jdbc;

    public TecnicoService(TecnicoRepository tecnicos, UsuarioRepository usuarios, UsuarioService usuarioService,
                          CatalogService catalog, JdbcTemplate jdbc) {
        this.tecnicos = tecnicos;
        this.usuarios = usuarios;
        this.usuarioService = usuarioService;
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    public record Carga(int ponderada, int abiertos) {}

    public record TecnicoDto(UUID id, String nombre, String email, String cargo, Long sedeId, Nivel nivel,
                             boolean disponible, boolean activo, int cargaMaxima, int carga, int abiertos,
                             List<String> especialidades, List<Long> especialidadIds, long resueltos) {}

    /** Carga actual: tickets abiertos asignados, ponderados por prioridad (baja 1 ... crítica 4). */
    public Map<UUID, Carga> cargas() {
        Map<UUID, Carga> out = new HashMap<>();
        jdbc.query("""
                SELECT t.tecnico_id, COALESCE(SUM(p.peso),0) AS carga, COUNT(*) AS abiertos
                FROM tickets t JOIN prioridades p ON p.codigo = t.prioridad_codigo
                WHERE t.estado_codigo IN ('PENDIENTE','EN_CURSO') AND t.tecnico_id IS NOT NULL
                GROUP BY t.tecnico_id""",
                rs -> { out.put(rs.getObject(1, UUID.class), new Carga(rs.getInt(2), rs.getInt(3))); });
        return out;
    }

    public Map<UUID, Long> resueltos() {
        Map<UUID, Long> out = new HashMap<>();
        jdbc.query("SELECT tecnico_id, COUNT(*) FROM tickets WHERE estado_codigo = 'FINALIZADA' AND tecnico_id IS NOT NULL GROUP BY tecnico_id",
                rs -> { out.put(rs.getObject(1, UUID.class), rs.getLong(2)); });
        return out;
    }

    @Transactional(readOnly = true)
    public List<TecnicoDto> listar() {
        Map<UUID, Carga> cargas = cargas();
        Map<UUID, Long> resueltos = resueltos();
        Map<Long, Especialidad> esp = catalog.especialidadesPorId();
        return tecnicos.findAll().stream()
                .map(t -> toDto(t, usuarios.findById(t.getUsuarioId()).orElseThrow(), cargas, resueltos, esp))
                .sorted(Comparator.comparing(TecnicoDto::nombre))
                .toList();
    }

    @Transactional(readOnly = true)
    public TecnicoDto obtener(UUID id) {
        Tecnico t = tecnicos.findById(id).orElseThrow(() -> ApiException.notFound("Técnico"));
        return toDto(t, usuarios.findById(id).orElseThrow(), cargas(), resueltos(), catalog.especialidadesPorId());
    }

    @Transactional
    public TecnicoDto crear(String email, String password, String nombre, String cargo, Long sedeId,
                            Nivel nivel, int cargaMaxima, Set<Long> especialidadIds) {
        validarEspecialidades(especialidadIds);
        Usuario u = usuarioService.crear(new UsuarioService.CrearUsuario(email, password, nombre, cargo,
                "Soporte técnico", sedeId, Rol.TECNICO));
        Tecnico t = new Tecnico();
        t.setUsuarioId(u.getId());
        t.setNivel(nivel);
        t.setCargaMaxima(cargaMaxima);
        t.setEspecialidadIds(new HashSet<>(especialidadIds));
        tecnicos.save(t);
        return obtener(u.getId());
    }

    @Transactional
    public TecnicoDto actualizar(UUID id, Nivel nivel, Integer cargaMaxima, Boolean disponible, Set<Long> especialidadIds) {
        Tecnico t = tecnicos.findById(id).orElseThrow(() -> ApiException.notFound("Técnico"));
        if (nivel != null) t.setNivel(nivel);
        if (cargaMaxima != null) {
            if (cargaMaxima < 1) throw ApiException.badRequest("CARGA_INVALIDA", "La carga máxima debe ser mayor que cero");
            t.setCargaMaxima(cargaMaxima);
        }
        if (disponible != null) t.setDisponible(disponible);
        if (especialidadIds != null) {
            validarEspecialidades(especialidadIds);
            t.getEspecialidadIds().clear();
            t.getEspecialidadIds().addAll(especialidadIds);
        }
        tecnicos.save(t);
        return obtener(id);
    }

    private void validarEspecialidades(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw ApiException.badRequest("SIN_ESPECIALIDAD", "El técnico debe tener al menos una especialidad");
        }
        Map<Long, Especialidad> esp = catalog.especialidadesPorId();
        for (Long id : ids) if (!esp.containsKey(id)) throw ApiException.notFound("Especialidad " + id);
    }

    private TecnicoDto toDto(Tecnico t, Usuario u, Map<UUID, Carga> cargas, Map<UUID, Long> resueltos,
                             Map<Long, Especialidad> esp) {
        Carga c = cargas.getOrDefault(t.getUsuarioId(), new Carga(0, 0));
        List<Long> ids = t.getEspecialidadIds().stream().sorted().toList();
        return new TecnicoDto(t.getUsuarioId(), u.getNombre(), u.getEmail(), u.getCargo(), u.getSedeId(), t.getNivel(),
                t.isDisponible(), u.isActivo(), t.getCargaMaxima(), c.ponderada(), c.abiertos(),
                ids.stream().map(esp::get).filter(e -> e != null).map(Especialidad::getNombre).toList(), ids,
                resueltos.getOrDefault(t.getUsuarioId(), 0L));
    }
}
