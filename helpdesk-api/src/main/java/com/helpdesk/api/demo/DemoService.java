package com.helpdesk.api.demo;

import com.helpdesk.api.assignment.AssignmentScorer;
import com.helpdesk.api.assignment.AssignmentService;
import com.helpdesk.api.catalog.CatalogService;
import com.helpdesk.api.catalog.Categoria;
import com.helpdesk.api.catalog.Equipo;
import com.helpdesk.api.catalog.EquipoRepository;
import com.helpdesk.api.catalog.Especialidad;
import com.helpdesk.api.catalog.Sede;
import com.helpdesk.api.catalog.TipoFalla;
import com.helpdesk.api.identity.Rol;
import com.helpdesk.api.identity.Usuario;
import com.helpdesk.api.identity.UsuarioRepository;
import com.helpdesk.api.identity.UsuarioService;
import com.helpdesk.api.shared.Maps;
import com.helpdesk.api.shared.OutboxService;
import com.helpdesk.api.shared.ProcessedEventStore;
import com.helpdesk.api.shared.TimeProvider;
import com.helpdesk.api.technician.Nivel;
import com.helpdesk.api.technician.Tecnico;
import com.helpdesk.api.technician.TecnicoRepository;
import com.helpdesk.api.ticket.Canal;
import com.helpdesk.api.ticket.EstadoTicket;
import com.helpdesk.api.ticket.Impacto;
import com.helpdesk.api.ticket.Ticket;
import com.helpdesk.api.ticket.TicketDtos;
import com.helpdesk.api.ticket.TicketRepository;
import com.helpdesk.api.ticket.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Generador de ESCENARIOS DE DEMOSTRACIÓN y de CARGA. Recorre el ciclo de vida real de los tickets
 * (servicios de dominio, outbox, eventos, Data Lake) con fechas en el pasado, en orden cronológico.
 *
 * <p>Realismo de la distribución: crecimiento de la demanda en el tiempo, más carga entre semana y en horas
 * pico, festivos fijos de Colombia con poca actividad, estacionalidad mensual, incidentes masivos de red por
 * sede, usuarios que reportan más que otros, fallas recurrentes del mismo equipo, cancelaciones, reaperturas,
 * encuestas y una IA cuyo acierto mejora con el tiempo.
 *
 * <p>Todo lo que produce queda marcado con es_demo = true y canal DEMO, y el diagnóstico de IA es SIMULADO
 * (proveedor "demo-simulado"). Sirve para probar el pipeline, el rendimiento y los tableros; no debe
 * presentarse como datos reales de la organización. Es determinista: la misma semilla produce los mismos datos.
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);
    private static final ZoneId ZONA = ZoneId.of("America/Bogota");
    public static final String DEMO_PASSWORD = "Demo1234!";
    private static final String DOMINIO = "@demo.helpdesk.local";

    /** Parámetros de un escenario. {@code hasta} es inclusivo; los tickets nunca quedan en el futuro. */
    public record Parametros(LocalDate desde, LocalDate hasta, int tickets, int usuarios, int tecnicos,
                             int administradores, long semilla) {}

    private final TicketService tickets;
    private final TicketRepository ticketRepo;
    private final AssignmentService assignment;
    private final UsuarioRepository usuarios;
    private final UsuarioService usuarioService;
    private final TecnicoRepository tecnicos;
    private final EquipoRepository equipoRepo;
    private final CatalogService catalog;
    private final OutboxService outbox;
    private final ProcessedEventStore processed;
    private final TimeProvider time;
    private final TransactionTemplate tx;
    private final AtomicReference<Map<String, Object>> ultimo = new AtomicReference<>(Map.of("estado", "SIN_EJECUTAR"));

    public DemoService(TicketService tickets, TicketRepository ticketRepo, AssignmentService assignment,
                       UsuarioRepository usuarios, UsuarioService usuarioService, TecnicoRepository tecnicos,
                       EquipoRepository equipoRepo, CatalogService catalog, OutboxService outbox,
                       ProcessedEventStore processed, TimeProvider time, TransactionTemplate tx) {
        this.tickets = tickets;
        this.ticketRepo = ticketRepo;
        this.assignment = assignment;
        this.usuarios = usuarios;
        this.usuarioService = usuarioService;
        this.tecnicos = tecnicos;
        this.equipoRepo = equipoRepo;
        this.catalog = catalog;
        this.outbox = outbox;
        this.processed = processed;
        this.time = time;
        this.tx = tx;
    }

    public Map<String, Object> estado() { return ultimo.get(); }

    // ------------------------------------------------------------------ Textos

    private static final Map<String, String[]> DESCRIPCIONES = Map.ofEntries(
            Map.entry("HW_NO_ENCIENDE", new String[]{"El computador no enciende, presiono el botón y no prende ninguna luz.",
                    "El portátil se apagó y ya no vuelve a encender aunque está conectado al cargador.",
                    "El equipo hace un pitido al encender y la pantalla se queda en negro."}),
            Map.entry("HW_PANTALLA", new String[]{"La pantalla del monitor parpadea y aparecen líneas de colores.",
                    "El monitor se queda en negro aunque el equipo está encendido.",
                    "La pantalla del portátil se ve muy oscura y con una mancha en una esquina."}),
            Map.entry("HW_DANO_FISICO", new String[]{"Se cayó el portátil y la bisagra quedó rota, el conector de carga está flojo.",
                    "El puerto USB del equipo está doblado y no reconoce dispositivos.",
                    "Se derramó café sobre el teclado del portátil y algunas teclas quedaron pegadas."}),
            Map.entry("HW_RENDIMIENTO", new String[]{"El equipo está muy lento y se calienta mucho, el ventilador suena fuerte.",
                    "Tarda más de diez minutos en iniciar y se congela al abrir programas.",
                    "El computador se pone lento cuando abro varias pestañas y se reinicia solo."}),
            Map.entry("SW_ERROR_SISTEMA", new String[]{"Aparece una pantalla azul de Windows con un error y el equipo se reinicia.",
                    "Windows muestra un mensaje de error al iniciar sesión y no carga el escritorio.",
                    "El sistema dice que el perfil de usuario está dañado y abre un escritorio temporal."}),
            Map.entry("SW_APLICACION", new String[]{"El programa de facturación se cierra solo cuando intento guardar.",
                    "Excel muestra un error y no abre los archivos compartidos.",
                    "El ERP se queda cargando al generar el reporte mensual y luego se cierra."}),
            Map.entry("SW_INSTALACION", new String[]{"Necesito instalar el software de diseño para un proyecto nuevo.",
                    "La actualización de Windows falló y quedó a medias.",
                    "Solicito la instalación del cliente de videoconferencias en mi equipo."}),
            Map.entry("RED_SIN_CONEXION", new String[]{"No tengo internet en el equipo, aparece sin conexión a la red.",
                    "Toda el área se quedó sin acceso a la red y a los sistemas internos.",
                    "Los sistemas internos no cargan y el navegador dice que no hay conexión."}),
            Map.entry("RED_WIFI", new String[]{"El wifi de la sala de reuniones se desconecta a cada rato.",
                    "No puedo conectarme a la red inalámbrica, dice que la contraseña es incorrecta.",
                    "La señal de wifi es muy débil en mi puesto y las llamadas se cortan."}),
            Map.entry("RED_CABLEADO", new String[]{"El punto de red del puesto está dañado, el cable no hace contacto.",
                    "El cable de red está cortado y el equipo no tiene conexión.",
                    "La conexión por cable se cae cada vez que muevo el escritorio."}),
            Map.entry("IMP_ATASCO", new String[]{"La impresora tiene papel atascado y no deja imprimir.",
                    "La impresora arruga las hojas y se atasca en la bandeja dos.",
                    "Se quedó una hoja atrapada dentro de la impresora y no la puedo sacar."}),
            Map.entry("IMP_ERROR", new String[]{"La impresora muestra un error en la pantalla y una luz naranja parpadea.",
                    "Sale un mensaje de error de tóner en la impresora aunque se cambió.",
                    "La impresora imprime las hojas con rayas negras y manchas."}),
            Map.entry("IMP_CONEXION", new String[]{"La impresora aparece desconectada en mi computador.",
                    "No encuentro la impresora de la red al enviar documentos.",
                    "Los documentos quedan en cola y la impresora nunca los recibe."}),
            Map.entry("PER_TECLADO_MOUSE", new String[]{"El teclado no responde y algunas teclas no escriben.",
                    "El mouse inalámbrico no funciona aunque cambié las pilas.",
                    "El cursor del mouse se mueve solo y hace clic sin que lo toque."}),
            Map.entry("PER_AUDIO_VIDEO", new String[]{"El proyector de la sala no muestra imagen desde el portátil.",
                    "La cámara web no funciona en las videollamadas.",
                    "No se escucha el audio en las reuniones virtuales aunque el volumen está alto."}),
            Map.entry("ACC_CONTRASENA", new String[]{"Mi cuenta quedó bloqueada por intentos fallidos de contraseña.",
                    "Olvidé la contraseña y no puedo iniciar sesión.",
                    "El sistema me pide cambiar la contraseña pero no acepta la nueva."}),
            Map.entry("ACC_PERMISOS", new String[]{"No tengo permisos para abrir la carpeta compartida del área.",
                    "Necesito acceso al sistema de inventario para mi nuevo cargo.",
                    "Me aparece acceso denegado al entrar al módulo de reportes."}),
            Map.entry("ACC_CORREO", new String[]{"El correo no sincroniza y no me llegan mensajes desde ayer.",
                    "Outlook pide la contraseña todo el tiempo y no envía correos.",
                    "Los correos con adjuntos se quedan en la bandeja de salida."}));

    private static final String[] SALUDOS = {"", "", "Buenos días. ", "Hola, ", "Buenas tardes. ", "Por favor, me ayudan: "};
    private static final String[] DESDE_CUANDO = {"Pasa desde esta mañana.", "Empezó ayer en la tarde.", "Lleva así dos días.",
            "Sucede de forma intermitente.", "Comenzó después de la última actualización.", "Ocurrió hace una hora.",
            "Desde el lunes viene fallando.", "Es la segunda vez esta semana."};
    private static final String[] CONSECUENCIAS = {"No puedo avanzar con mi trabajo.", "Es urgente por el cierre de mes.",
            "Tengo una reunión con un cliente en un rato.", "Puedo trabajar, pero con dificultad.",
            "Varios compañeros tienen el mismo problema.", "Ya reinicié el equipo y sigue igual.", "",
            "Necesito resolverlo antes del final del día."};

    private static final Map<String, String[]> SOLUCIONES = Map.of(
            "HARDWARE", new String[]{"Se revisó el hardware, se reemplazó la pieza defectuosa y se probaron todos los componentes.",
                    "Se limpió el equipo internamente, se cambió la pasta térmica y se verificó la temperatura.",
                    "Se ajustaron las conexiones internas y se hicieron pruebas de encendido y rendimiento."},
            "SOFTWARE", new String[]{"Se reinstaló la aplicación, se aplicaron las actualizaciones y se verificó con el usuario.",
                    "Se reparó el perfil de usuario y se restauró la configuración del sistema.",
                    "Se instaló el software solicitado con su licencia y se comprobó su funcionamiento."},
            "REDES", new String[]{"Se reemplazó el cable de red y se certificó el punto con el probador.",
                    "Se reinició el equipo de red de la zona y se renovó la configuración IP del equipo.",
                    "Se reconfiguró el perfil de wifi y se ajustó el canal del punto de acceso."},
            "IMPRESION", new String[]{"Se retiró el papel atascado, se limpiaron los rodillos y se imprimió una página de prueba.",
                    "Se reemplazó el tóner y se calibró la impresora.",
                    "Se reinstaló el controlador de la impresora y se volvió a agregar en la red."},
            "PERIFERICOS", new String[]{"Se reemplazó el periférico dañado y se verificó su funcionamiento.",
                    "Se actualizaron los controladores del dispositivo y se configuró nuevamente.",
                    "Se ajustó la configuración de audio y video y se probó en una videollamada."},
            "ACCESOS", new String[]{"Se desbloqueó la cuenta y se acompañó al usuario en el cambio de contraseña.",
                    "Se asignaron los permisos aprobados por el jefe de área y se validó el acceso.",
                    "Se reconfiguró la cuenta de correo y se sincronizó el buzón."});

    private static final String[] ACTIVIDADES = {"Diagnóstico inicial con el usuario", "Revisión remota del equipo",
            "Visita al puesto de trabajo", "Pruebas de funcionamiento", "Escalamiento al proveedor",
            "Seguimiento con el usuario", "Reemplazo de componente", "Actualización de controladores"};

    private static final String[] COMENTARIOS_ENCUESTA = {"Muy buena atención, rápida y clara.", "El técnico fue muy amable.",
            "Se demoró un poco, pero quedó funcionando.", "Excelente servicio.", "Tuve que insistir para que me atendieran.",
            "Me explicaron cómo evitar que vuelva a pasar."};

    /** Frecuencia relativa de cada tipo de falla. */
    private static final Map<String, Integer> PESOS = Map.ofEntries(
            Map.entry("IMP_ATASCO", 9), Map.entry("RED_WIFI", 8), Map.entry("ACC_CONTRASENA", 8),
            Map.entry("SW_APLICACION", 7), Map.entry("HW_RENDIMIENTO", 6), Map.entry("IMP_ERROR", 5),
            Map.entry("ACC_CORREO", 5), Map.entry("RED_SIN_CONEXION", 4), Map.entry("SW_ERROR_SISTEMA", 4),
            Map.entry("HW_PANTALLA", 4), Map.entry("PER_TECLADO_MOUSE", 4), Map.entry("ACC_PERMISOS", 3),
            Map.entry("PER_AUDIO_VIDEO", 3), Map.entry("IMP_CONEXION", 3), Map.entry("SW_INSTALACION", 3),
            Map.entry("HW_NO_ENCIENDE", 2), Map.entry("RED_CABLEADO", 2), Map.entry("HW_DANO_FISICO", 2));

    private static final String[] TIPOS_INCIDENTE = {"RED_SIN_CONEXION", "RED_SIN_CONEXION", "RED_WIFI", "SW_ERROR_SISTEMA", "ACC_CORREO"};

    /** Festivos de fecha fija en Colombia (los trasladables se omiten): muy poca actividad. */
    private static final Set<MonthDay> FESTIVOS = Set.of(MonthDay.of(1, 1), MonthDay.of(5, 1), MonthDay.of(7, 20),
            MonthDay.of(8, 7), MonthDay.of(12, 8), MonthDay.of(12, 25));

    // ------------------------------------------------------------------ Personas

    private static final String[] NOMBRES = {"Ana", "Carlos", "Diana", "Esteban", "Felipe", "Gloria", "Hernán", "Isabel",
            "Jorge", "Karen", "Luis", "Marcela", "Nicolás", "Olga", "Pablo", "Paula", "Ricardo", "Sandra", "Tomás",
            "Natalia", "Andrea", "Sebastián", "Juliana", "David", "Daniela", "Alejandro", "Catalina", "Santiago",
            "Mónica", "Javier", "Lorena", "Óscar", "Adriana", "Fernando", "Claudia", "Mauricio", "Liliana", "Germán",
            "Patricia", "Hugo", "Viviana", "Cristian", "Yolanda", "Iván", "Carolina", "Rafael", "Ángela", "Manuel"};
    private static final String[] APELLIDOS = {"García", "Rodríguez", "Martínez", "López", "González", "Hernández",
            "Pérez", "Sánchez", "Ramírez", "Torres", "Flórez", "Rivera", "Gómez", "Díaz", "Reyes", "Morales", "Jiménez",
            "Álvarez", "Ruiz", "Castro", "Vargas", "Ortiz", "Rojas", "Moreno", "Muñoz", "Suárez", "Herrera", "Medina",
            "Aguilar", "Cárdenas", "Ospina", "Restrepo", "Zapata", "Valencia", "Quintero", "Mejía", "Salazar", "Arango"};

    /** Área y cargos posibles; el peso indica qué tan grande es el área. */
    private static final Object[][] AREAS = {
            {"Finanzas", 10, new String[]{"Contador", "Analista financiero", "Auxiliar contable", "Tesorero"}},
            {"Comercial", 14, new String[]{"Ejecutivo de ventas", "Asesor comercial", "Coordinador comercial"}},
            {"Atención al cliente", 12, new String[]{"Agente de servicio", "Supervisor de servicio"}},
            {"Talento humano", 6, new String[]{"Analista de nómina", "Coordinador de bienestar", "Psicólogo de selección"}},
            {"Logística", 9, new String[]{"Jefe de bodega", "Auxiliar logístico", "Analista de inventarios"}},
            {"Compras", 5, new String[]{"Analista de compras", "Comprador"}},
            {"Mercadeo", 6, new String[]{"Diseñador", "Analista de mercadeo", "Community manager"}},
            {"Administración", 8, new String[]{"Auxiliar administrativo", "Asistente de gerencia", "Recepcionista"}},
            {"Jurídica", 4, new String[]{"Abogado", "Asistente jurídico"}},
            {"Producción", 10, new String[]{"Supervisor de planta", "Operario de producción", "Analista de calidad"}},
            {"Gerencia", 3, new String[]{"Gerente de área", "Director"}}};

    /** Primeros usuarios y técnicos: se conservan los nombres de versiones anteriores del generador. */
    private static final String[][] USUARIOS_BASE = {{"Ana María López", "Contadora", "Finanzas"},
            {"Carlos Martínez", "Analista de compras", "Compras"}, {"Diana Castro", "Coordinadora", "Talento humano"},
            {"Esteban Díaz", "Vendedor", "Comercial"}, {"Felipe Vargas", "Auxiliar administrativo", "Administración"},
            {"Gloria Ramírez", "Recepcionista", "Servicios"}, {"Hernán Ortiz", "Jefe de bodega", "Logística"},
            {"Isabel Moreno", "Diseñadora", "Mercadeo"}};
    private static final Object[][] TECNICOS_BASE = {
            {"Laura Gómez", Nivel.SENIOR, new String[]{"ESP_HARDWARE", "ESP_PANTALLAS", "ESP_PERIFERICOS"}},
            {"Andrés Rojas", Nivel.SEMI_SENIOR, new String[]{"ESP_REDES", "ESP_WIFI"}},
            {"Camila Torres", Nivel.SENIOR, new String[]{"ESP_SOFTWARE", "ESP_SO", "ESP_ACCESOS", "ESP_CORREO"}},
            {"Julián Pérez", Nivel.JUNIOR, new String[]{"ESP_IMPRESORAS", "ESP_ATASCOS", "ESP_PERIFERICOS"}},
            {"Valentina Ruiz", Nivel.SEMI_SENIOR, new String[]{"ESP_SOFTWARE", "ESP_ACCESOS", "ESP_REDES"}},
            {"Mateo Herrera", Nivel.JUNIOR, new String[]{"ESP_HARDWARE", "ESP_IMPRESORAS", "ESP_AUDIOVISUAL"}}};

    /** Especialidad general y específicas de cada categoría (para generar técnicos que cubran todo el catálogo). */
    private static final String[][] ESPECIALIDADES_POR_CATEGORIA = {
            {"ESP_HARDWARE", "ESP_PANTALLAS"}, {"ESP_SOFTWARE", "ESP_SO"}, {"ESP_REDES", "ESP_WIFI"},
            {"ESP_IMPRESORAS", "ESP_ATASCOS"}, {"ESP_PERIFERICOS", "ESP_AUDIOVISUAL"}, {"ESP_ACCESOS", "ESP_CORREO"}};

    private static final String[][] MARCAS_PC = {{"Lenovo", "ThinkPad E14"}, {"Dell", "Latitude 5440"}, {"HP", "ProBook 440"},
            {"Dell", "OptiPlex 7010"}, {"Lenovo", "ThinkCentre M70"}, {"HP", "ProDesk 400"}, {"Asus", "ExpertBook B1"}};
    private static final String[][] MARCAS_IMP = {{"HP", "LaserJet M404"}, {"Epson", "EcoTank L6270"},
            {"Brother", "HL-L5100DN"}, {"Kyocera", "Ecosys M2040"}};

    // ------------------------------------------------------------------ Estado interno de una ejecución

    private record Plan(Instant t0, String tipoForzado, Long sedeForzada, boolean incidente) {}

    private record Previo(Long equipoId, Usuario usuario, String tipo, Instant cierre) {}

    private record Contexto(Random rnd, Instant ahora, Instant inicio, Instant fin, Map<String, TipoFalla> tipos,
                            Map<Long, Categoria> cats, List<String> bolsa, List<Usuario> users, double[] pesoUsuario,
                            Map<Long, List<Integer>> usuariosPorSede, Map<UUID, Long> pcPorUsuario,
                            Map<Long, List<Long>> impresorasPorSede, Map<Long, List<Long>> proyectoresPorSede,
                            List<UUID> techs, List<UUID> admins, Set<String> descripciones, List<Previo> previos) {}

    private static final class Conteo {
        int creados, finalizados, cancelados, reabiertos, manuales, incidentes, errores;
    }

    // ------------------------------------------------------------------ API

    @Async
    public void generar(Parametros p, UUID adminSolicitante) {
        Instant inicio = Instant.now();
        ultimo.set(progreso(inicio, p, 0, new Conteo()));
        try {
            ultimo.set(ejecutar(p, adminSolicitante, inicio));
        } catch (RuntimeException e) {
            log.error("Falló el escenario de demostración", e);
            ultimo.set(Map.of("estado", "ERROR", "error", String.valueOf(e.getMessage())));
        }
    }

    Map<String, Object> ejecutar(Parametros p, UUID adminSolicitante, Instant inicioEjecucion) {
        Random rnd = new Random(p.semilla());
        Instant ahora = Instant.now();
        Instant inicio = p.desde().atStartOfDay(ZONA).toInstant();
        Instant fin = p.hasta().plusDays(1).atStartOfDay(ZONA).toInstant();
        if (fin.isAfter(ahora)) fin = ahora;

        Map<String, TipoFalla> tipos = catalog.tiposPorId().values().stream()
                .collect(Collectors.toMap(TipoFalla::getCodigo, t -> t));
        Map<Long, Categoria> cats = catalog.categoriasPorId();
        List<Sede> sedes = catalog.sedesPorId().values().stream().sorted(Comparator.comparing(Sede::getCodigo)).toList();

        // 1) Personas y equipos (idempotente: reutiliza lo que ya exista por correo o código de inventario)
        List<UUID> admins = asegurarAdministradores(p.administradores(), sedes, inicio);
        if (admins.isEmpty()) admins = List.of(adminSolicitante);
        List<UUID> techs = asegurarTecnicos(p.tecnicos(), sedes, inicio, rnd);
        List<Usuario> users = asegurarUsuarios(p.usuarios(), sedes, inicio, rnd);
        int equiposAntes = (int) equipoRepo.count();
        Map<UUID, Long> pcPorUsuario = asegurarPcs(users, sedes, rnd);
        Map<Long, List<Long>> impresoras = asegurarEquiposDeSede("IMPRESORA", "IMP",
                Map.of("SEDE_BOGOTA", 4, "SEDE_BELLO", 3, "SEDE_GIRARDOT", 2), sedes);
        Map<Long, List<Long>> proyectores = asegurarEquiposDeSede("PROYECTOR", "PRY",
                Map.of("SEDE_BOGOTA", 3, "SEDE_BELLO", 2, "SEDE_GIRARDOT", 1), sedes);
        int equiposCreados = (int) equipoRepo.count() - equiposAntes;

        // Peso de cada usuario (unos reportan mucho más que otros) y usuarios por sede
        double[] pesoUsuario = new double[users.size()];
        List<Integer> orden = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) orden.add(i);
        java.util.Collections.shuffle(orden, rnd);
        for (int r = 0; r < orden.size(); r++) pesoUsuario[orden.get(r)] = 1.0 / Math.pow(r + 1, 0.55);
        Map<Long, List<Integer>> porSede = new HashMap<>();
        for (int i = 0; i < users.size(); i++) porSede.computeIfAbsent(users.get(i).getSedeId(), k -> new ArrayList<>()).add(i);

        List<String> bolsa = new ArrayList<>();
        PESOS.forEach((k, v) -> { if (tipos.containsKey(k)) for (int i = 0; i < v; i++) bolsa.add(k); });
        bolsa.sort(String::compareTo);

        Contexto c = new Contexto(rnd, ahora, inicio, fin, tipos, cats, bolsa, users, pesoUsuario, porSede, pcPorUsuario,
                impresoras, proyectores, techs, admins, new HashSet<>(), new ArrayList<>());

        // 2) Calendario de tickets, en orden cronológico
        List<Plan> planes = planificar(p.tickets(), c, sedes);
        Conteo n = new Conteo();
        for (int i = 0; i < planes.size(); i++) {
            Plan plan = planes.get(i);
            try {
                // Una transacción por ticket: todo su ciclo de vida se confirma junto (más rápido y consistente).
                tx.executeWithoutResult(s -> procesar(plan, c, n));
            } catch (RuntimeException e) {
                n.errores++;
                if (n.errores <= 20) log.warn("Ticket de demostración {} omitido: {}", i + 1, e.getMessage());
            }
            if ((i + 1) % 250 == 0) ultimo.set(progreso(inicioEjecucion, p, i + 1, n));
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("estado", "COMPLETADO");
        r.put("rango", p.desde() + " a " + p.hasta());
        r.put("ticketsCreados", n.creados);
        r.put("finalizados", n.finalizados);
        r.put("cancelados", n.cancelados);
        r.put("reabiertos", n.reabiertos);
        r.put("asignadosManualmente", n.manuales);
        r.put("ticketsDeIncidentesMasivos", n.incidentes);
        r.put("omitidosPorError", n.errores);
        r.put("usuarios", users.size());
        r.put("tecnicos", techs.size());
        r.put("administradores", admins.size());
        r.put("equiposCreados", equiposCreados);
        r.put("duracionMin", Math.round(Duration.between(inicioEjecucion, Instant.now()).toSeconds() / 6.0) / 10.0);
        r.put("fin", Instant.now().toString());
        r.put("nota", "Datos marcados es_demo=true. Espera a que se publiquen los eventos y ejecuta el pipeline del Data Lake.");
        return r;
    }

    private static Map<String, Object> progreso(Instant inicio, Parametros p, int procesados, Conteo n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("estado", "EN_PROCESO");
        m.put("inicio", inicio.toString());
        m.put("procesados", procesados);
        m.put("total", p.tickets());
        m.put("porcentaje", p.tickets() == 0 ? 0 : Math.round(procesados * 1000.0 / p.tickets()) / 10.0);
        m.put("errores", n.errores);
        return m;
    }

    // ------------------------------------------------------------------ Calendario

    /** Peso de un día: tendencia creciente, día de la semana, mes y festivos. */
    private static double pesoDia(LocalDate d, LocalDate desde, long totalDias) {
        double tendencia = 0.7 + 0.6 * (ChronoUnit.DAYS.between(desde, d) / (double) Math.max(1, totalDias));
        double semana = switch (d.getDayOfWeek()) {
            case MONDAY -> 1.30; case TUESDAY -> 1.15; case WEDNESDAY -> 1.10; case THURSDAY -> 1.05;
            case FRIDAY -> 0.90; case SATURDAY -> 0.22; case SUNDAY -> 0.08;
        };
        double mes = switch (d.getMonthValue()) {
            case 1 -> 1.15; case 2, 3 -> 1.05; case 6, 7 -> 0.92; case 12 -> 0.75; default -> 1.0;
        };
        double festivo = FESTIVOS.contains(MonthDay.from(d)) ? 0.15 : 1.0;
        return tendencia * semana * mes * festivo;
    }

    private List<Plan> planificar(int cantidad, Contexto c, List<Sede> sedes) {
        Random rnd = c.rnd();
        LocalDate desde = LocalDate.ofInstant(c.inicio(), ZONA);
        LocalDate hasta = LocalDate.ofInstant(c.fin(), ZONA);
        long totalDias = ChronoUnit.DAYS.between(desde, hasta) + 1;
        double[] acumulado = new double[(int) totalDias];
        double suma = 0;
        for (int i = 0; i < totalDias; i++) {
            suma += pesoDia(desde.plusDays(i), desde, totalDias);
            acumulado[i] = suma;
        }
        List<Plan> planes = new ArrayList<>(cantidad);

        // Incidentes masivos: ~1 por cada 1.000 tickets, de 8 a 35 reportes en la misma sede en pocas horas.
        int incidentes = cantidad >= 500 ? cantidad / 1000 : 0;
        for (int k = 0; k < incidentes && planes.size() < cantidad; k++) {
            LocalDate dia = desde.plusDays(buscar(acumulado, rnd.nextDouble() * suma));
            if (dia.getDayOfWeek().getValue() >= 6) dia = dia.minusDays(dia.getDayOfWeek().getValue() - 5L);
            Instant base = dia.atTime(8 + rnd.nextInt(8), rnd.nextInt(60)).atZone(ZONA).toInstant();
            String tipo = TIPOS_INCIDENTE[rnd.nextInt(TIPOS_INCIDENTE.length)];
            Long sede = sedes.get(rnd.nextInt(sedes.size())).getId();
            int tam = 8 + rnd.nextInt(28);
            for (int j = 0; j < tam && planes.size() < cantidad; j++) {
                Instant t = base.plusSeconds(rnd.nextInt(3 * 3600));
                if (t.isBefore(c.fin()) && t.isAfter(c.inicio())) planes.add(new Plan(t, tipo, sede, true));
            }
        }
        while (planes.size() < cantidad) {
            LocalDate dia = desde.plusDays(buscar(acumulado, rnd.nextDouble() * suma));
            Instant t = dia.atTime(horaDelDia(rnd)).atZone(ZONA).toInstant();
            if (t.isBefore(c.inicio())) continue;
            if (!t.isBefore(c.fin())) t = c.fin().minusSeconds(60 + rnd.nextInt(6 * 3600));
            planes.add(new Plan(t, null, null, false));
        }
        planes.sort(Comparator.comparing(Plan::t0));
        return planes;
    }

    private static int buscar(double[] acumulado, double x) {
        int i = Arrays.binarySearch(acumulado, x);
        return Math.min(acumulado.length - 1, i >= 0 ? i : -i - 1);
    }

    /** Hora de reporte: picos a media mañana y media tarde dentro de la jornada laboral. */
    private static LocalTime horaDelDia(Random rnd) {
        double x = rnd.nextDouble();
        int hora;
        if (x < 0.40) hora = 8 + rnd.nextInt(4);        // 8:00 – 11:59
        else if (x < 0.75) hora = 14 + rnd.nextInt(3);  // 14:00 – 16:59
        else if (x < 0.97) hora = 7 + rnd.nextInt(12);  // 7:00 – 18:59
        else hora = 19 + rnd.nextInt(3);                // fuera de horario
        return LocalTime.of(hora, rnd.nextInt(60), rnd.nextInt(60));
    }

    // ------------------------------------------------------------------ Ciclo de vida de un ticket

    private void procesar(Plan plan, Contexto c, Conteo n) {
        Random rnd = c.rnd();
        Instant t0 = plan.t0();
        String tipoCodigo;
        Usuario user;
        Long equipoId;

        Previo base = plan.incidente() ? null : recurrente(c, t0);
        if (base != null) {
            // Falla recurrente: mismo usuario, equipo y tipo poco después del cierre anterior
            tipoCodigo = base.tipo();
            user = base.usuario();
            equipoId = base.equipoId();
        } else {
            tipoCodigo = plan.tipoForzado() != null ? plan.tipoForzado() : c.bolsa().get(rnd.nextInt(c.bolsa().size()));
            user = elegirUsuario(c, plan.sedeForzada());
            equipoId = elegirEquipo(tipoCodigo, user, c);
        }
        TipoFalla tipo = c.tipos().get(tipoCodigo);
        String catCodigo = c.cats().get(tipo.getCategoriaId()).getCodigo();
        Impacto impacto = plan.incidente() ? (rnd.nextDouble() < 0.6 ? Impacto.AREA : Impacto.SEDE)
                : rnd.nextDouble() < 0.72 ? Impacto.INDIVIDUAL : rnd.nextDouble() < 0.8 ? Impacto.AREA : Impacto.SEDE;
        boolean bloquea = plan.incidente() ? rnd.nextDouble() < 0.8 : rnd.nextDouble() < 0.33;
        String descripcion = describir(tipoCodigo, equipoId, t0, c);
        TicketDtos.CrearTicketRequest req = new TicketDtos.CrearTicketRequest(descripcion, user.getSedeId(), equipoId,
                impacto, bloquea);

        UUID ticketId = time.at(t0, () -> tickets.crearComo(user, req, List.of(), Canal.DEMO, true).getId());
        n.creados++;
        if (plan.incidente()) n.incidentes++;

        // 1) Diagnóstico de IA SIMULADO (1–3 minutos después). El acierto mejora con el tiempo (72 % → 90 %).
        Instant t1 = t0.plus(Duration.ofSeconds(40 + rnd.nextInt(140)));
        double avance = Duration.between(c.inicio(), t0).toSeconds() / (double) Math.max(1, Duration.between(c.inicio(), c.fin()).toSeconds());
        boolean acierta = rnd.nextDouble() < 0.72 + 0.18 * avance;
        String propuesto = acierta ? tipoCodigo : otroTipo(tipoCodigo, c.tipos(), rnd);
        double conf = acierta ? 0.68 + rnd.nextDouble() * 0.29 : 0.40 + rnd.nextDouble() * 0.38;
        String catProp = c.cats().get(c.tipos().get(propuesto).getCategoriaId()).getCodigo();
        simularIa(ticketId, t1, propuesto, catProp, conf);
        Instant cursor = t1;

        // 2) Cancelación temprana por el solicitante (~3 %)
        if (!plan.incidente() && rnd.nextDouble() < 0.03) {
            Instant tc = cursor.plus(Duration.ofMinutes(5 + rnd.nextInt(180)));
            if (tc.isBefore(c.ahora())) {
                step(ticketId, tc, tk -> tickets.cambiarEstadoInterno(tk, EstadoTicket.CANCELADA, null, null,
                        "El usuario resolvió el problema por su cuenta", user.getId(), Rol.USER));
                n.cancelados++;
            }
            return;
        }

        // 3) Asignación manual si quedó en revisión (20 min – 3 h después), por uno de los administradores
        Ticket t = ticketRepo.findById(ticketId).orElseThrow();
        if (t.getTecnicoId() == null) {
            cursor = cursor.plus(Duration.ofMinutes(20 + rnd.nextInt(160)));
            if (cursor.isAfter(c.ahora())) return;
            UUID elegido = elegirManual(ticketId, tipo, c.techs(), rnd);
            if (elegido == null) return;
            UUID admin = c.admins().get(rnd.nextDouble() < 0.6 ? 0 : rnd.nextInt(c.admins().size()));
            step(ticketId, cursor, tk -> {
                if (!tipoCodigo.equals(tk.getIaTipoFallaCodigo())) {   // el administrador corrige la clasificación
                    tk.setCategoriaId(tipo.getCategoriaId());
                    tk.setTipoFallaId(tipo.getId());
                }
                assignment.asignarManual(tk, elegido, "Revisión del administrador (escenario de demostración)", admin);
            });
            n.manuales++;
        }

        // 4) Inicio de atención (los tickets de las últimas 48 h pueden seguir pendientes)
        boolean reciente = Duration.between(t0, c.ahora()).toHours() < 48;
        if (reciente && rnd.nextDouble() < 0.5) return;
        Ticket actual = ticketRepo.findById(ticketId).orElseThrow();
        cursor = cursor.plus(Duration.ofMinutes(prioridadAlta(actual) ? 5 + rnd.nextInt(40) : 10 + rnd.nextInt(230)));
        if (cursor.isAfter(c.ahora())) return;
        UUID tec = actual.getTecnicoId();
        step(ticketId, cursor, tk -> tickets.cambiarEstadoInterno(tk, EstadoTicket.EN_CURSO, null, null,
                "Inicio de atención", tec, Rol.TECNICO));

        // 5) Actividades
        int nAct = 1 + rnd.nextInt(3);
        for (int a = 0; a < nAct; a++) {
            cursor = cursor.plus(Duration.ofMinutes(15 + rnd.nextInt(90)));
            if (cursor.isAfter(c.ahora())) break;
            int minutos = 10 + rnd.nextInt(110);
            String tipoAct = a == 0 ? "DIAGNOSTICO" : rnd.nextBoolean() ? "REPARACION" : "SEGUIMIENTO";
            String detalle = ACTIVIDADES[rnd.nextInt(ACTIVIDADES.length)];
            step(ticketId, cursor, tk -> tickets.registrarActividadInterna(tk, tec, tipoAct, detalle, minutos));
        }
        if (reciente && rnd.nextDouble() < 0.4) return;

        // 6) Finalización según prioridad
        Duration duracion = duracionPorPrioridad(actual.getPrioridad().name(), rnd);
        cursor = cursor.plus(duracion);
        if (cursor.isAfter(c.ahora())) return;
        String[] sols = SOLUCIONES.getOrDefault(catCodigo, SOLUCIONES.get("SOFTWARE"));
        String solucion = sols[rnd.nextInt(sols.length)];
        step(ticketId, cursor, tk -> tickets.cambiarEstadoInterno(tk, EstadoTicket.FINALIZADA, solucion, tipoCodigo,
                null, tec, Rol.TECNICO));
        n.finalizados++;
        Instant cierre = cursor;

        // 7) Encuesta de satisfacción (~70 %), con comentario en una de cada cuatro
        if (rnd.nextDouble() < 0.7) {
            Instant te = cierre.plus(Duration.ofHours(1 + rnd.nextInt(30)));
            if (te.isBefore(c.ahora())) {
                int nota = Math.min(5, duracion.toHours() > 24 ? 2 + rnd.nextInt(3) : 3 + rnd.nextInt(3));
                String comentario = rnd.nextDouble() < 0.25 ? COMENTARIOS_ENCUESTA[rnd.nextInt(COMENTARIOS_ENCUESTA.length)] : null;
                step(ticketId, te, tk -> tickets.calificarInterno(tk, user.getId(), nota, comentario));
            }
        }

        // 8) Reapertura ocasional (~5 %) por el solicitante dentro de los 3 días siguientes
        if (rnd.nextDouble() < 0.05) {
            Instant tr = cierre.plus(Duration.ofHours(2 + rnd.nextInt(70)));
            Instant tf = tr.plus(Duration.ofHours(1 + rnd.nextInt(8)));
            if (tf.isBefore(c.ahora())) {
                step(ticketId, tr, tk -> tickets.cambiarEstadoInterno(tk, EstadoTicket.EN_CURSO, null, null,
                        "El problema volvió a presentarse", user.getId(), Rol.USER));
                step(ticketId, tf, tk -> tickets.cambiarEstadoInterno(tk, EstadoTicket.FINALIZADA,
                        "Se corrigió nuevamente la falla y se verificó con el usuario.", tipoCodigo, null, tec, Rol.TECNICO));
                n.reabiertos++;
                cierre = tf;
            }
        }
        c.previos().add(new Previo(equipoId, user, tipoCodigo, cierre));
    }

    private static boolean prioridadAlta(Ticket t) {
        String p = t.getPrioridad().name();
        return p.equals("ALTA") || p.equals("CRITICA");
    }

    /** ~8 % de los tickets repite una falla cerrada en los 30 días anteriores (reincidencia). */
    private static Previo recurrente(Contexto c, Instant t0) {
        List<Previo> previos = c.previos();
        if (previos.isEmpty() || c.rnd().nextDouble() >= 0.08) return null;
        List<Previo> candidatos = new ArrayList<>();
        for (int i = previos.size() - 1; i >= 0 && candidatos.size() < 200; i--) {
            Previo p = previos.get(i);
            if (p.cierre().isBefore(t0.minus(Duration.ofDays(30)))) {
                if (previos.size() - i > 600) break;
                continue;
            }
            if (p.cierre().isBefore(t0.minus(Duration.ofHours(4)))) candidatos.add(p);
        }
        return candidatos.isEmpty() ? null : candidatos.get(c.rnd().nextInt(candidatos.size()));
    }

    private static Usuario elegirUsuario(Contexto c, Long sedeForzada) {
        List<Integer> indices = sedeForzada == null ? null : c.usuariosPorSede().get(sedeForzada);
        double total = 0;
        if (indices == null || indices.isEmpty()) {
            for (double w : c.pesoUsuario()) total += w;
            double x = c.rnd().nextDouble() * total;
            for (int i = 0; i < c.users().size(); i++) {
                x -= c.pesoUsuario()[i];
                if (x <= 0) return c.users().get(i);
            }
            return c.users().get(c.users().size() - 1);
        }
        for (int i : indices) total += c.pesoUsuario()[i];
        double x = c.rnd().nextDouble() * total;
        for (int i : indices) {
            x -= c.pesoUsuario()[i];
            if (x <= 0) return c.users().get(i);
        }
        return c.users().get(indices.get(indices.size() - 1));
    }

    private static Long elegirEquipo(String tipo, Usuario user, Contexto c) {
        Random rnd = c.rnd();
        if (tipo.startsWith("ACC_") || tipo.startsWith("RED_") && rnd.nextDouble() < 0.5) return null;
        if (tipo.startsWith("IMP_")) return alguno(c.impresorasPorSede().get(user.getSedeId()), rnd);
        if (tipo.equals("PER_AUDIO_VIDEO") && rnd.nextDouble() < 0.6) return alguno(c.proyectoresPorSede().get(user.getSedeId()), rnd);
        return rnd.nextDouble() < 0.88 ? c.pcPorUsuario().get(user.getId()) : null;
    }

    private static Long alguno(List<Long> ids, Random rnd) {
        return ids == null || ids.isEmpty() ? null : ids.get(rnd.nextInt(ids.size()));
    }

    /** Descripción variada y sin duplicados: frase base + saludo + desde cuándo + consecuencia (+ detalle). */
    private String describir(String tipo, Long equipoId, Instant t0, Contexto c) {
        Random rnd = c.rnd();
        String[] bases = DESCRIPCIONES.get(tipo);
        for (int intento = 0; intento < 6; intento++) {
            StringBuilder sb = new StringBuilder();
            sb.append(SALUDOS[rnd.nextInt(SALUDOS.length)]);
            String base = bases[rnd.nextInt(bases.length)];
            sb.append(sb.isEmpty() ? base : Character.toLowerCase(base.charAt(0)) + base.substring(1));
            sb.append(' ').append(DESDE_CUANDO[rnd.nextInt(DESDE_CUANDO.length)]);
            String cons = CONSECUENCIAS[rnd.nextInt(CONSECUENCIAS.length)];
            if (!cons.isEmpty()) sb.append(' ').append(cons);
            if (intento >= 2) {
                ZonedDateTime z = t0.atZone(ZONA);
                sb.append(String.format(" Lo noté hacia las %d:%02d.", z.getHour(), z.getMinute()));
            }
            if (intento >= 4 && equipoId != null) sb.append(" Equipo de inventario n.º ").append(equipoId).append('.');
            String d = sb.toString();
            if (c.descripciones().add(d)) return d;
        }
        String d = DESCRIPCIONES.get(tipo)[0] + " Reporte del " + t0.atZone(ZONA).toLocalDateTime().withNano(0) + ".";
        c.descripciones().add(d);
        return d;
    }

    private void simularIa(UUID ticketId, Instant at, String tipo, String categoria, double conf) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> diag = Maps.of("origen", "TEXTO", "adjunto_id", null, "tipo_equipo", null,
                "categoria_codigo", categoria, "tipo_falla_codigo", tipo,
                "descripcion", "Diagnóstico simulado para el escenario de demostración",
                "causa_probable", null, "confianza", Math.round(conf * 1000) / 1000.0, "concluyente", true,
                "evidencias", List.of(), "recomendacion", null, "especialidad_sugerida", null, "latencia_ms", 0);
        Map<String, Object> payload = Maps.of("ticket_id", ticketId.toString(), "trigger", "TICKET_CREATED",
                "proveedor", "demo-simulado", "modelo", "simulacion-v1", "es_demo", true,
                "diagnosticos", List.of(diag),
                "combinado", Maps.of("categoria_codigo", categoria, "tipo_falla_codigo", tipo,
                        "confianza", Math.round(conf * 1000) / 1000.0));
        time.at(at, () -> {
            tx.executeWithoutResult(s -> {
                processed.markIfNew(eventId); // este servicio no debe volver a procesar su propio evento
                outbox.record(eventId, "Ticket", ticketId, "AiDiagnosisCompleted", payload, "demo-simulado");
                tickets.aplicarResultadoIa(payload);
            });
            return null;
        });
    }

    private void step(UUID ticketId, Instant at, Consumer<Ticket> work) {
        time.at(at, () -> {
            tx.executeWithoutResult(s -> work.accept(ticketRepo.findById(ticketId).orElseThrow()));
            return null;
        });
    }

    private UUID elegirManual(UUID ticketId, TipoFalla tipoReal, List<UUID> techs, Random rnd) {
        Ticket t = ticketRepo.findById(ticketId).orElseThrow();
        Long catOriginal = t.getCategoriaId();
        Long tipoOriginal = t.getTipoFallaId();
        t.setCategoriaId(tipoReal.getCategoriaId());
        t.setTipoFallaId(tipoReal.getId());
        AssignmentScorer.Result r = assignment.evaluar(t, 1.0);
        t.setCategoriaId(catOriginal);
        t.setTipoFallaId(tipoOriginal);
        if (r.ranking().isEmpty()) return techs.isEmpty() ? null : techs.get(rnd.nextInt(techs.size()));
        if (r.ranking().size() > 1 && rnd.nextDouble() < 0.15) return r.ranking().get(1).id();
        return r.ranking().get(0).id();
    }

    private static String otroTipo(String real, Map<String, TipoFalla> tipos, Random rnd) {
        TipoFalla tr = tipos.get(real);
        List<String> misma = tipos.values().stream().filter(t -> t.getCategoriaId().equals(tr.getCategoriaId())
                && !t.getCodigo().equals(real)).map(TipoFalla::getCodigo).sorted().toList();
        if (!misma.isEmpty() && rnd.nextDouble() < 0.6) return misma.get(rnd.nextInt(misma.size()));
        List<String> todos = tipos.keySet().stream().filter(k -> !k.equals(real)).sorted().toList();
        return todos.get(rnd.nextInt(todos.size()));
    }

    private static Duration duracionPorPrioridad(String p, Random rnd) {
        return switch (p) {
            case "CRITICA" -> Duration.ofMinutes(60 + rnd.nextInt(300));
            case "ALTA" -> Duration.ofMinutes(120 + rnd.nextInt(600));
            case "MEDIA" -> Duration.ofMinutes(240 + rnd.nextInt(2600));
            default -> Duration.ofMinutes(480 + rnd.nextInt(5300));
        };
    }

    // ------------------------------------------------------------------ Personas y equipos

    private static String abreviatura(Sede s) {
        String c = s.getCodigo().replace("SEDE_", "");
        return c.substring(0, Math.min(3, c.length()));   // SEDE_BOGOTA -> BOG, SEDE_BELLO -> BEL, SEDE_GIRARDOT -> GIR
    }

    /** Peso de cada sede en la población: Bogotá es la sede principal; Girardot, un centro regional más pequeño. */
    private static final Map<String, Double> PESO_SEDE = Map.of("SEDE_BOGOTA", 0.55, "SEDE_BELLO", 0.30, "SEDE_GIRARDOT", 0.15);

    /** Sede de la persona i según PESO_SEDE (o reparto uniforme si el catálogo tiene otras sedes). */
    private static Sede sedePara(int i, List<Sede> sedes, Random rnd) {
        if (sedes.stream().allMatch(s -> PESO_SEDE.containsKey(s.getCodigo()))) {
            double x = rnd.nextDouble();
            for (Sede s : sedes) {
                x -= PESO_SEDE.get(s.getCodigo());
                if (x <= 0) return s;
            }
            return sedes.get(sedes.size() - 1);
        }
        return sedes.get(i % sedes.size());
    }

    private Usuario crearUsuario(String email, String nombre, String cargo, String area, Long sedeId, Rol rol, Instant alta) {
        return usuarios.findByEmailIgnoreCase(email).orElseGet(() -> time.at(alta, () -> tx.execute(s ->
                usuarioService.crear(new UsuarioService.CrearUsuario(email, DEMO_PASSWORD, nombre, cargo, area, sedeId, rol)))));
    }

    private List<UUID> asegurarAdministradores(int cantidad, List<Sede> sedes, Instant alta) {
        String[][] defs = {{"Martha Cecilia Duarte", "Coordinadora de mesa de ayuda"}, {"Ricardo Andrés Pineda", "Jefe de infraestructura TI"}};
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            String nombre = i < defs.length ? defs[i][0] : "Administrador " + (i + 1);
            String cargo = i < defs.length ? defs[i][1] : "Administrador de soporte";
            out.add(crearUsuario("admin" + (i + 1) + DOMINIO, nombre, cargo, "Tecnología",
                    sedes.get(i % sedes.size()).getId(), Rol.ADMIN, alta).getId());
        }
        return out;
    }

    private List<UUID> asegurarTecnicos(int cantidad, List<Sede> sedes, Instant alta, Random rnd) {
        Map<String, Long> esp = catalog.especialidadesPorId().values().stream()
                .collect(Collectors.toMap(Especialidad::getCodigo, Especialidad::getId));
        Set<String> nombresUsados = new HashSet<>();
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            String email = "tecnico" + (i + 1) + DOMINIO;
            Usuario existente = usuarios.findByEmailIgnoreCase(email).orElse(null);
            if (existente != null) {
                nombresUsados.add(existente.getNombre());
                out.add(existente.getId());
                continue;
            }
            String nombre;
            Nivel nivel;
            String[] especialidades;
            if (i < TECNICOS_BASE.length) {
                nombre = (String) TECNICOS_BASE[i][0];
                nivel = (Nivel) TECNICOS_BASE[i][1];
                especialidades = (String[]) TECNICOS_BASE[i][2];
            } else {
                nombre = nombreUnico(i * 7 + 3, nombresUsados);
                // 1 de cada 5 senior, 2 semi-senior y 2 junior; categoría principal rotativa para cubrir todo
                nivel = i % 5 == 0 ? Nivel.SENIOR : i % 5 <= 2 ? Nivel.SEMI_SENIOR : Nivel.JUNIOR;
                String[] principal = ESPECIALIDADES_POR_CATEGORIA[i % ESPECIALIDADES_POR_CATEGORIA.length];
                List<String> lista = new ArrayList<>(List.of(principal[0]));
                if (rnd.nextDouble() < 0.6) lista.add(principal[1]);
                if (nivel != Nivel.JUNIOR || rnd.nextDouble() < 0.3) {
                    String[] secundaria = ESPECIALIDADES_POR_CATEGORIA[(i + 1 + rnd.nextInt(5)) % ESPECIALIDADES_POR_CATEGORIA.length];
                    lista.add(secundaria[0]);
                }
                especialidades = lista.toArray(String[]::new);
            }
            nombresUsados.add(nombre);
            int carga = nivel == Nivel.SENIOR ? 14 : nivel == Nivel.SEMI_SENIOR ? 12 : 8;
            Long sedeId = sedes.get(i % sedes.size()).getId();
            Nivel nivelFinal = nivel;
            String[] espFinal = especialidades;
            Usuario u = time.at(alta, () -> tx.execute(s -> {
                Usuario nu = usuarioService.crear(new UsuarioService.CrearUsuario(email, DEMO_PASSWORD, nombre,
                        "Técnico de soporte", "Soporte técnico", sedeId, Rol.TECNICO));
                Tecnico t = new Tecnico();
                t.setUsuarioId(nu.getId());
                t.setNivel(nivelFinal);
                t.setCargaMaxima(carga);
                Set<Long> ids = new HashSet<>();
                for (String codigo : espFinal) if (esp.containsKey(codigo)) ids.add(esp.get(codigo));
                t.setEspecialidadIds(ids);
                tecnicos.save(t);
                return nu;
            }));
            out.add(u.getId());
        }
        return out;
    }

    private List<Usuario> asegurarUsuarios(int cantidad, List<Sede> sedes, Instant alta, Random rnd) {
        Set<String> nombresUsados = new HashSet<>();
        for (String[] b : USUARIOS_BASE) nombresUsados.add(b[0]);
        int pesoTotal = 0;
        for (Object[] a : AREAS) pesoTotal += (Integer) a[1];
        List<Usuario> out = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            String email = "usuario" + (i + 1) + DOMINIO;
            Usuario existente = usuarios.findByEmailIgnoreCase(email).orElse(null);
            if (existente != null) { out.add(existente); continue; }
            String nombre, cargo, area;
            Long sedeId;
            if (i < USUARIOS_BASE.length) {
                nombre = USUARIOS_BASE[i][0];
                cargo = USUARIOS_BASE[i][1];
                area = USUARIOS_BASE[i][2];
                sedeId = sedes.get(i % sedes.size()).getId();
            } else {
                nombre = nombreUnico(i, nombresUsados);
                int x = rnd.nextInt(pesoTotal);
                Object[] elegida = AREAS[0];
                for (Object[] a : AREAS) { x -= (Integer) a[1]; if (x < 0) { elegida = a; break; } }
                area = (String) elegida[0];
                String[] cargos = (String[]) elegida[2];
                cargo = cargos[rnd.nextInt(cargos.length)];
                sedeId = sedePara(i, sedes, rnd).getId();
            }
            nombresUsados.add(nombre);
            out.add(crearUsuario(email, nombre, cargo, area, sedeId, Rol.USER, alta));
        }
        return out;
    }

    /** Nombre + dos apellidos, determinista a partir de la semilla i y sin repetir. */
    private static String nombreUnico(int i, Set<String> usados) {
        for (int k = 0; ; k++) {
            int s = i * 31 + k * 17;
            String n = NOMBRES[Math.floorMod(s, NOMBRES.length)] + " "
                    + APELLIDOS[Math.floorMod(s * 7 + 3, APELLIDOS.length)] + " "
                    + APELLIDOS[Math.floorMod(s * 13 + 11, APELLIDOS.length)];
            if (!usados.contains(n)) return n;
        }
    }

    /** Un computador por usuario (PC-BOG-101, …), en la sede del usuario. */
    private Map<UUID, Long> asegurarPcs(List<Usuario> users, List<Sede> sedes, Random rnd) {
        Map<Long, Sede> sedePorId = sedes.stream().collect(Collectors.toMap(Sede::getId, s -> s));
        Map<String, Equipo> porCodigo = equipoRepo.findAll().stream()
                .collect(Collectors.toMap(Equipo::getCodigoInventario, e -> e, (a, b) -> a));
        Map<UUID, Long> out = new HashMap<>();
        for (int i = 0; i < users.size(); i++) {
            Usuario u = users.get(i);
            Sede sede = sedePorId.getOrDefault(u.getSedeId(), sedes.get(0));
            String codigo = "PC-" + abreviatura(sede) + "-" + (101 + i);
            Equipo e = porCodigo.get(codigo);
            if (e == null) {
                String[] mm = MARCAS_PC[rnd.nextInt(MARCAS_PC.length)];
                boolean portatil = mm[1].contains("Book") || mm[1].contains("Latitude") || mm[1].contains("ThinkPad");
                e = guardarEquipo(codigo, portatil ? "PORTATIL" : "ESCRITORIO", mm[0], mm[1], sede.getId());
                porCodigo.put(codigo, e);
            }
            out.put(u.getId(), e.getId());
        }
        return out;
    }

    private Map<Long, List<Long>> asegurarEquiposDeSede(String tipo, String prefijo, Map<String, Integer> porSede, List<Sede> sedes) {
        Map<String, Equipo> porCodigo = equipoRepo.findAll().stream()
                .collect(Collectors.toMap(Equipo::getCodigoInventario, e -> e, (a, b) -> a));
        Map<Long, List<Long>> out = new HashMap<>();
        for (int s = 0; s < sedes.size(); s++) {
            Sede sede = sedes.get(s);
            List<Long> ids = new ArrayList<>();
            // Los equipos que ya existían en la sede también cuentan
            porCodigo.values().stream().filter(e -> e.getSedeId().equals(sede.getId()) && tipo.equals(e.getTipo()))
                    .map(Equipo::getId).forEach(ids::add);
            int cantidad = porSede.getOrDefault(sede.getCodigo(), 2);
            for (int k = 0; k < cantidad; k++) {
                String codigo = prefijo + "-" + abreviatura(sede) + "-" + (11 + k);
                if (porCodigo.containsKey(codigo)) continue;
                String[] mm = tipo.equals("IMPRESORA") ? MARCAS_IMP[(s + k) % MARCAS_IMP.length] : new String[]{"Epson", "PowerLite X49"};
                Equipo e = guardarEquipo(codigo, tipo, mm[0], mm[1], sede.getId());
                porCodigo.put(codigo, e);
                ids.add(e.getId());
            }
            out.put(sede.getId(), ids);
        }
        return out;
    }

    private Equipo guardarEquipo(String codigo, String tipo, String marca, String modelo, Long sedeId) {
        return tx.execute(st -> {
            Equipo e = new Equipo();
            e.setCodigoInventario(codigo);
            e.setTipo(tipo);
            e.setMarca(marca);
            e.setModelo(modelo);
            e.setSedeId(sedeId);
            return equipoRepo.save(e);
        });
    }
}
