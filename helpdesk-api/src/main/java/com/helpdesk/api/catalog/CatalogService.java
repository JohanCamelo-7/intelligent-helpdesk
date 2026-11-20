package com.helpdesk.api.catalog;

import com.helpdesk.api.shared.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Acceso a catálogos (tablas pequeñas; se consultan directamente). */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final SedeRepository sedes;
    private final EquipoRepository equipos;
    private final CategoriaRepository categorias;
    private final TipoFallaRepository tipos;
    private final EspecialidadRepository especialidades;

    public CatalogService(SedeRepository sedes, EquipoRepository equipos, CategoriaRepository categorias,
                          TipoFallaRepository tipos, EspecialidadRepository especialidades) {
        this.sedes = sedes;
        this.equipos = equipos;
        this.categorias = categorias;
        this.tipos = tipos;
        this.especialidades = especialidades;
    }

    public Sede sede(Long id) { return sedes.findById(id).orElseThrow(() -> ApiException.notFound("Sede")); }
    public Optional<Equipo> equipo(Long id) { return id == null ? Optional.empty() : equipos.findById(id); }
    public Optional<Categoria> categoria(Long id) { return id == null ? Optional.empty() : categorias.findById(id); }
    public Optional<TipoFalla> tipoFalla(Long id) { return id == null ? Optional.empty() : tipos.findById(id); }
    public Optional<Categoria> categoriaPorCodigo(String c) { return c == null ? Optional.empty() : categorias.findByCodigo(c); }
    public Optional<TipoFalla> tipoPorCodigo(String c) { return c == null ? Optional.empty() : tipos.findByCodigo(c); }

    public Map<Long, Sede> sedesPorId() { return sedes.findAll().stream().collect(Collectors.toMap(Sede::getId, Function.identity())); }
    public Map<Long, Equipo> equiposPorId() { return equipos.findAll().stream().collect(Collectors.toMap(Equipo::getId, Function.identity())); }
    public Map<Long, Categoria> categoriasPorId() { return categorias.findAll().stream().collect(Collectors.toMap(Categoria::getId, Function.identity())); }
    public Map<Long, TipoFalla> tiposPorId() { return tipos.findAll().stream().collect(Collectors.toMap(TipoFalla::getId, Function.identity())); }
    public Map<Long, Especialidad> especialidadesPorId() { return especialidades.findAll().stream().collect(Collectors.toMap(Especialidad::getId, Function.identity())); }

    public record CatalogoDto(List<Item> sedes, List<EquipoItem> equipos, List<Item> categorias,
                              List<TipoItem> tiposFalla, List<EspItem> especialidades,
                              List<Item> prioridades, List<Item> estados) {}
    public record Item(Long id, String codigo, String nombre) {}
    public record EquipoItem(Long id, String codigo, String tipo, String marca, String modelo, Long sedeId) {}
    public record TipoItem(Long id, String codigo, String nombre, Long categoriaId, String categoriaCodigo) {}
    public record EspItem(Long id, String codigo, String nombre, Long categoriaId, Long tipoFallaId) {}

    public CatalogoDto snapshot() {
        Map<Long, Categoria> cats = categoriasPorId();
        return new CatalogoDto(
                sedes.findAllByOrderByNombreAsc().stream().map(s -> new Item(s.getId(), s.getCodigo(), s.getNombre() + " (" + s.getCiudad() + ")")).toList(),
                equipos.findAllByOrderByCodigoInventarioAsc().stream().filter(Equipo::isActivo)
                        .map(e -> new EquipoItem(e.getId(), e.getCodigoInventario(), e.getTipo(), e.getMarca(), e.getModelo(), e.getSedeId())).toList(),
                categorias.findAllByOrderByNombreAsc().stream().map(c -> new Item(c.getId(), c.getCodigo(), c.getNombre())).toList(),
                tipos.findAllByOrderByNombreAsc().stream().map(t -> new TipoItem(t.getId(), t.getCodigo(), t.getNombre(),
                        t.getCategoriaId(), cats.get(t.getCategoriaId()).getCodigo())).toList(),
                especialidades.findAllByOrderByNombreAsc().stream().map(e -> new EspItem(e.getId(), e.getCodigo(), e.getNombre(),
                        e.getCategoriaId(), e.getTipoFallaId())).toList(),
                List.of(new Item(1L, "BAJA", "Baja"), new Item(2L, "MEDIA", "Media"),
                        new Item(3L, "ALTA", "Alta"), new Item(4L, "CRITICA", "Crítica")),
                List.of(new Item(1L, "PENDIENTE", "Pendiente"), new Item(2L, "EN_CURSO", "En curso"),
                        new Item(3L, "FINALIZADA", "Finalizada"), new Item(4L, "CANCELADA", "Cancelada")));
    }

    @Transactional
    public Sede crearSede(String codigo, String nombre, String ciudad) {
        if (sedes.findByCodigo(codigo).isPresent()) throw ApiException.conflict("CODIGO_EXISTE", "Ya existe esa sede");
        Sede s = new Sede();
        s.setCodigo(codigo.toUpperCase());
        s.setNombre(nombre);
        s.setCiudad(ciudad);
        return sedes.save(s);
    }

    @Transactional
    public Equipo crearEquipo(String codigo, String tipo, String marca, String modelo, Long sedeId) {
        sede(sedeId);
        Equipo e = new Equipo();
        e.setCodigoInventario(codigo.toUpperCase());
        e.setTipo(tipo.toUpperCase());
        e.setMarca(marca);
        e.setModelo(modelo);
        e.setSedeId(sedeId);
        return equipos.save(e);
    }
}
