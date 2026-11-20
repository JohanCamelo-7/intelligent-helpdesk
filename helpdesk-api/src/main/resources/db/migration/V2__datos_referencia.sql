-- Datos de referencia. Sedes y equipos son un CATÁLOGO DE PRUEBA:
-- reemplázalos por el inventario real de la organización.

INSERT INTO prioridades (codigo, nombre, peso, orden) VALUES
 ('BAJA', 'Baja', 1, 1), ('MEDIA', 'Media', 2, 2), ('ALTA', 'Alta', 3, 3), ('CRITICA', 'Crítica', 4, 4);

INSERT INTO estados (codigo, nombre, orden, abierto) VALUES
 ('PENDIENTE', 'Pendiente', 1, TRUE), ('EN_CURSO', 'En curso', 2, TRUE),
 ('FINALIZADA', 'Finalizada', 3, FALSE), ('CANCELADA', 'Cancelada', 4, FALSE);

INSERT INTO categorias (codigo, nombre) VALUES
 ('HARDWARE', 'Hardware'), ('SOFTWARE', 'Software y sistema operativo'), ('REDES', 'Redes y conectividad'),
 ('IMPRESION', 'Impresión'), ('PERIFERICOS', 'Periféricos'), ('ACCESOS', 'Cuentas y accesos');

INSERT INTO tipos_falla (codigo, nombre, categoria_id)
SELECT v.codigo, v.nombre, c.id FROM (VALUES
 ('HW_NO_ENCIENDE',    'El equipo no enciende',                 'HARDWARE'),
 ('HW_PANTALLA',       'Falla de pantalla o monitor',           'HARDWARE'),
 ('HW_DANO_FISICO',    'Daño físico del equipo o conector',     'HARDWARE'),
 ('HW_RENDIMIENTO',    'Lentitud o sobrecalentamiento',         'HARDWARE'),
 ('SW_ERROR_SISTEMA',  'Error del sistema operativo',           'SOFTWARE'),
 ('SW_APLICACION',     'Falla de una aplicación',               'SOFTWARE'),
 ('SW_INSTALACION',    'Instalación o actualización de software','SOFTWARE'),
 ('RED_SIN_CONEXION',  'Sin conexión a la red o internet',      'REDES'),
 ('RED_WIFI',          'Problemas de wifi',                     'REDES'),
 ('RED_CABLEADO',      'Cable o punto de red dañado',           'REDES'),
 ('IMP_ATASCO',        'Atasco de papel',                       'IMPRESION'),
 ('IMP_ERROR',         'Error o mensaje en la impresora',       'IMPRESION'),
 ('IMP_CONEXION',      'La impresora no se conecta',            'IMPRESION'),
 ('PER_TECLADO_MOUSE', 'Teclado o mouse',                       'PERIFERICOS'),
 ('PER_AUDIO_VIDEO',   'Audio, cámara o proyector',             'PERIFERICOS'),
 ('ACC_CONTRASENA',    'Contraseña o bloqueo de cuenta',        'ACCESOS'),
 ('ACC_PERMISOS',      'Permisos de acceso',                    'ACCESOS'),
 ('ACC_CORREO',        'Correo electrónico',                    'ACCESOS')
) AS v(codigo, nombre, cat) JOIN categorias c ON c.codigo = v.cat;

-- Especialidades: generales por categoría (tipo_falla NULL) y específicas por tipo de falla.
INSERT INTO especialidades (codigo, nombre, categoria_id, tipo_falla_id)
SELECT v.codigo, v.nombre, c.id, tf.id FROM (VALUES
 ('ESP_HARDWARE',   'Soporte de hardware',          'HARDWARE',    NULL),
 ('ESP_PANTALLAS',  'Pantallas y monitores',        'HARDWARE',    'HW_PANTALLA'),
 ('ESP_SOFTWARE',   'Soporte de software',          'SOFTWARE',    NULL),
 ('ESP_SO',         'Sistemas operativos',          'SOFTWARE',    'SW_ERROR_SISTEMA'),
 ('ESP_REDES',      'Redes',                        'REDES',       NULL),
 ('ESP_WIFI',       'Redes inalámbricas',           'REDES',       'RED_WIFI'),
 ('ESP_IMPRESORAS', 'Impresoras',                   'IMPRESION',   NULL),
 ('ESP_ATASCOS',    'Mecánica de impresoras',       'IMPRESION',   'IMP_ATASCO'),
 ('ESP_PERIFERICOS','Periféricos',                  'PERIFERICOS', NULL),
 ('ESP_AUDIOVISUAL','Audio y video',                'PERIFERICOS', 'PER_AUDIO_VIDEO'),
 ('ESP_ACCESOS',    'Cuentas y accesos',            'ACCESOS',     NULL),
 ('ESP_CORREO',     'Correo electrónico',           'ACCESOS',     'ACC_CORREO')
) AS v(codigo, nombre, cat, tf)
JOIN categorias c ON c.codigo = v.cat
LEFT JOIN tipos_falla tf ON tf.codigo = v.tf;

-- Sedes reales de UNIMINUTO (fuente: uniminuto.edu, «Encuentra tu sede»). Las direcciones no se incluyen.
INSERT INTO sedes (codigo, nombre, ciudad) VALUES
 ('SEDE_BOGOTA',   'UNIMINUTO Sede Principal Bogotá - Minuto de Dios (Engativá)', 'Bogotá'),
 ('SEDE_BELLO',    'UNIMINUTO Sede Bello (Rectoría Antioquia - Chocó)',          'Bello'),
 ('SEDE_GIRARDOT', 'UNIMINUTO Centro Regional Girardot',                         'Girardot');

INSERT INTO equipos (codigo_inventario, tipo, marca, modelo, sede_id)
SELECT v.cod, v.tipo, v.marca, v.modelo, s.id FROM (VALUES
 ('PC-BOG-001', 'PORTATIL',   'Lenovo', 'ThinkPad E14',     'SEDE_BOGOTA'),
 ('PC-BOG-002', 'ESCRITORIO', 'Dell',   'OptiPlex 7010',    'SEDE_BOGOTA'),
 ('IMP-BOG-01', 'IMPRESORA',  'HP',     'LaserJet M404',    'SEDE_BOGOTA'),
 ('PRY-BOG-01', 'PROYECTOR',  'Epson',  'PowerLite E20',    'SEDE_BOGOTA'),
 ('PC-BEL-001', 'PORTATIL',   'HP',     'ProBook 440',      'SEDE_BELLO'),
 ('PC-BEL-002', 'ESCRITORIO', 'Lenovo', 'ThinkCentre M70',  'SEDE_BELLO'),
 ('IMP-BEL-01', 'IMPRESORA',  'Epson',  'EcoTank L6270',    'SEDE_BELLO'),
 ('PC-GIR-001', 'PORTATIL',   'Dell',   'Latitude 5440',    'SEDE_GIRARDOT'),
 ('PC-GIR-002', 'ESCRITORIO', 'HP',     'ProDesk 400',      'SEDE_GIRARDOT'),
 ('IMP-GIR-01', 'IMPRESORA',  'Brother','HL-L5100DN',       'SEDE_GIRARDOT')
) AS v(cod, tipo, marca, modelo, sede) JOIN sedes s ON s.codigo = v.sede;

-- Configuración del algoritmo de asignación (editable por ADMIN)
INSERT INTO configuracion (clave, valor) VALUES
 ('asignacion.peso.especialidad', '0.40'),
 ('asignacion.peso.experiencia',  '0.20'),
 ('asignacion.peso.carga',        '0.25'),
 ('asignacion.peso.desempeno',    '0.15'),
 ('asignacion.umbral.confianza',  '0.75'),
 ('asignacion.umbral.puntaje',    '0.60'),
 ('asignacion.version',           'v1');
