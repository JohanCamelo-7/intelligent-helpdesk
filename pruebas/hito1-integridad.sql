-- =====================================================================
-- HITO 1 · Operación de integridad sobre el modelo relacional
--
-- Demuestra, contra la base real (PostgreSQL), que el esquema impide
-- datos inválidos y que el registro de un ticket es ATÓMICO: el ticket,
-- su historial y su evento de outbox se guardan juntos o no se guarda nada.
--
-- Todo corre dentro de una transacción que termina en ROLLBACK:
-- no deja ningún dato en la base.
--
-- Ejecutar (Git Bash, desde la carpeta del proyecto):
--   docker compose exec -T postgres psql -U postgres -d helpdesk -v ON_ERROR_STOP=1 < pruebas/hito1-integridad.sql
-- =====================================================================
\set QUIET on
\pset footer off
BEGIN;

-- Datos de apoyo (se descartan al final)
CREATE TEMP TABLE resultado (n int, prueba text, esperado text, obtenido text, ok boolean) ON COMMIT DROP;

INSERT INTO usuarios (id, email, password_hash, nombre, cargo, area, sede_id, rol)
SELECT '00000000-0000-0000-0000-0000000000a1', 'prueba.hito1@uniminuto.local', 'x', 'Usuario de prueba',
       'Docente', 'Académica', id, 'USER'
FROM sedes WHERE codigo = 'SEDE_GIRARDOT';

DO $$
DECLARE
  v_sede   bigint := (SELECT id FROM sedes WHERE codigo = 'SEDE_GIRARDOT');
  v_user   uuid   := '00000000-0000-0000-0000-0000000000a1';
  v_ticket uuid   := '00000000-0000-0000-0000-0000000000b1';
  antes    bigint;
  despues  bigint;

BEGIN
  -- 1. Operación atómica correcta: ticket + historial + evento en una sola transacción
  INSERT INTO tickets (id, numero, solicitante_id, cargo_solicitante, correo_solicitante, sede_id, descripcion,
                       impacto, canal, prioridad_codigo, estado_codigo, fecha_creacion)
  VALUES (v_ticket, 'PRUEBA-0001', v_user, 'Docente', 'prueba.hito1@uniminuto.local', v_sede,
          'El videobeam del salón 204 no proyecta imagen', 'AREA', 'WEB', 'MEDIA', 'PENDIENTE', now());
  INSERT INTO ticket_historial (id, ticket_id, tipo, valor_nuevo, usuario_id, fecha)
  VALUES (gen_random_uuid(), v_ticket, 'CREACION', 'PENDIENTE', v_user, now());
  INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, routing_key, envelope, occurred_at)
  VALUES (gen_random_uuid(), 'Ticket', v_ticket::text, 'TicketCreated', 'helpdesk.ticket.created', '{}', now());
  INSERT INTO resultado VALUES (1, 'Registrar ticket + historial + evento', 'se guardan los 3',
    (SELECT count(*) FROM tickets WHERE id = v_ticket) || ' ticket, '
    || (SELECT count(*) FROM ticket_historial WHERE ticket_id = v_ticket) || ' historial, '
    || (SELECT count(*) FROM outbox WHERE aggregate_id = v_ticket::text) || ' evento', true);

  -- 2. Atomicidad: si falla el evento, el ticket tampoco queda guardado
  antes := (SELECT count(*) FROM tickets);
  BEGIN
    INSERT INTO tickets (id, numero, solicitante_id, cargo_solicitante, correo_solicitante, sede_id, descripcion,
                         impacto, canal, prioridad_codigo, estado_codigo, fecha_creacion)
    VALUES (gen_random_uuid(), 'PRUEBA-0002', v_user, 'Docente', 'prueba.hito1@uniminuto.local', v_sede,
            'La impresora de la sala de profesores no imprime', 'AREA', 'WEB', 'MEDIA', 'PENDIENTE', now());
    INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, routing_key, envelope, occurred_at)
    VALUES (gen_random_uuid(), 'Ticket', 'x', 'TicketCreated', 'helpdesk.ticket.created', NULL, now()); -- falla: envelope NOT NULL
  EXCEPTION WHEN not_null_violation THEN NULL;
  END;
  despues := (SELECT count(*) FROM tickets);
  INSERT INTO resultado VALUES (2, 'Falla el evento a mitad de la operación', 'el ticket se revierte (0 nuevos)',
    (despues - antes) || ' tickets nuevos', despues = antes);

  -- 3..8. Restricciones del esquema: cada intento inválido debe ser rechazado
  BEGIN
    INSERT INTO tickets (id, numero, solicitante_id, cargo_solicitante, correo_solicitante, sede_id, descripcion,
                         impacto, canal, prioridad_codigo, estado_codigo, fecha_creacion)
    VALUES (gen_random_uuid(), 'PRUEBA-0001', v_user, 'Docente', 'x@x', v_sede, 'Número de ticket repetido',
            'AREA', 'WEB', 'MEDIA', 'PENDIENTE', now());
    INSERT INTO resultado VALUES (3, 'Número de ticket duplicado', 'rechazado (UNIQUE)', 'aceptado', false);
  EXCEPTION WHEN unique_violation THEN
    INSERT INTO resultado VALUES (3, 'Número de ticket duplicado', 'rechazado (UNIQUE)', 'rechazado: ' || SQLERRM, true);
  END;

  BEGIN
    INSERT INTO tickets (id, numero, solicitante_id, cargo_solicitante, correo_solicitante, sede_id, descripcion,
                         impacto, canal, prioridad_codigo, estado_codigo, fecha_creacion)
    VALUES (gen_random_uuid(), 'PRUEBA-0003', v_user, 'Docente', 'x@x', 999999, 'Sede que no existe en el país',
            'AREA', 'WEB', 'MEDIA', 'PENDIENTE', now());
    INSERT INTO resultado VALUES (4, 'Ticket en una sede inexistente', 'rechazado (FK)', 'aceptado', false);
  EXCEPTION WHEN foreign_key_violation THEN
    INSERT INTO resultado VALUES (4, 'Ticket en una sede inexistente', 'rechazado (FK)', 'rechazado: ' || SQLERRM, true);
  END;

  BEGIN
    INSERT INTO tickets (id, numero, solicitante_id, cargo_solicitante, correo_solicitante, sede_id, descripcion,
                         impacto, canal, prioridad_codigo, estado_codigo, fecha_creacion)
    VALUES (gen_random_uuid(), 'PRUEBA-0004', v_user, 'Docente', 'x@x', v_sede, 'Estado fuera del catálogo',
            'AREA', 'WEB', 'MEDIA', 'RESUELTO_SIN_REVISAR', now());
    INSERT INTO resultado VALUES (5, 'Estado que no está en el catálogo', 'rechazado (FK)', 'aceptado', false);
  EXCEPTION WHEN foreign_key_violation THEN
    INSERT INTO resultado VALUES (5, 'Estado que no está en el catálogo', 'rechazado (FK)', 'rechazado: ' || SQLERRM, true);
  END;

  BEGIN
    INSERT INTO tickets (id, numero, solicitante_id, cargo_solicitante, correo_solicitante, sede_id, descripcion,
                         impacto, canal, prioridad_codigo, estado_codigo, fecha_creacion, fecha_finalizacion)
    VALUES (gen_random_uuid(), 'PRUEBA-0005', v_user, 'Docente', 'x@x', v_sede, 'Cerrado antes de haberse creado',
            'AREA', 'WEB', 'MEDIA', 'FINALIZADA', now(), now() - interval '1 day');
    INSERT INTO resultado VALUES (6, 'Fecha de cierre anterior a la creación', 'rechazado (CHECK ck_fechas)', 'aceptado', false);
  EXCEPTION WHEN check_violation THEN
    INSERT INTO resultado VALUES (6, 'Fecha de cierre anterior a la creación', 'rechazado (CHECK ck_fechas)', 'rechazado: ' || SQLERRM, true);
  END;

  BEGIN
    INSERT INTO encuestas (id, ticket_id, usuario_id, calificacion, fecha)
    VALUES (gen_random_uuid(), v_ticket, v_user, 7, now());
    INSERT INTO resultado VALUES (7, 'Encuesta con calificación 7 (escala 1-5)', 'rechazado (CHECK)', 'aceptado', false);
  EXCEPTION WHEN check_violation THEN
    INSERT INTO resultado VALUES (7, 'Encuesta con calificación 7 (escala 1-5)', 'rechazado (CHECK)', 'rechazado: ' || SQLERRM, true);
  END;

  BEGIN
    DELETE FROM sedes WHERE id = v_sede;
    INSERT INTO resultado VALUES (8, 'Borrar una sede que tiene tickets', 'rechazado (FK)', 'aceptado', false);
  EXCEPTION WHEN foreign_key_violation THEN
    INSERT INTO resultado VALUES (8, 'Borrar una sede que tiene tickets', 'rechazado (FK)', 'rechazado: ' || SQLERRM, true);
  END;

  -- 9. Borrado en cascada controlado: al borrar el ticket se van sus dependientes, no quedan huérfanos
  DELETE FROM tickets WHERE id = v_ticket;
  INSERT INTO resultado VALUES (9, 'Borrar el ticket de prueba', 'su historial se borra en cascada',
    (SELECT count(*) FROM ticket_historial WHERE ticket_id = v_ticket) || ' filas de historial huérfanas',
    NOT EXISTS (SELECT 1 FROM ticket_historial WHERE ticket_id = v_ticket));
END $$;

\echo
\echo '=== HITO 1 · Pruebas de integridad (todo se revierte al final) ==='
SELECT n AS "#", prueba, esperado, left(obtenido, 70) AS obtenido, CASE WHEN ok THEN 'OK' ELSE 'FALLA' END AS resultado
FROM resultado ORDER BY n;

\echo '=== Integridad referencial de los datos reales (todas deben dar 0) ==='
SELECT 'tickets sin solicitante' AS revision, count(*) AS filas FROM tickets t
  WHERE NOT EXISTS (SELECT 1 FROM usuarios u WHERE u.id = t.solicitante_id)
UNION ALL SELECT 'tickets con sede inexistente', count(*) FROM tickets t
  WHERE NOT EXISTS (SELECT 1 FROM sedes s WHERE s.id = t.sede_id)
UNION ALL SELECT 'tickets cerrados sin fecha de cierre', count(*) FROM tickets
  WHERE estado_codigo = 'FINALIZADA' AND fecha_finalizacion IS NULL
UNION ALL SELECT 'tickets sin evento de creación en el outbox', count(*) FROM tickets t
  WHERE NOT EXISTS (SELECT 1 FROM outbox o WHERE o.aggregate_id = t.id::text AND o.event_type = 'TicketCreated');

\echo '=== Tickets por sede (datos reales) ==='
SELECT s.nombre AS sede, s.ciudad, count(t.id) AS tickets,
       round(100.0 * count(t.id) / NULLIF(sum(count(t.id)) OVER (), 0), 1) AS porcentaje
FROM sedes s LEFT JOIN tickets t ON t.sede_id = s.id GROUP BY s.nombre, s.ciudad ORDER BY 3 DESC;

ROLLBACK;
