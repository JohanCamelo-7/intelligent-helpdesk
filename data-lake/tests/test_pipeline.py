"""Prueba de extremo a extremo del pipeline Landing -> Bronze -> Silver -> Gold con Spark local.

Los eventos replican EXACTAMENTE el formato que emite helpdesk-api (sobre + payload) y
ai-service. Las tablas se guardan como Parquet en una carpeta temporal (modo local).
"""
import json
import os
import uuid
from datetime import date, datetime, timedelta, timezone

import pytest

TODAY = date(2026, 11, 12)
T0 = datetime(2026, 11, 2, 14, 0, tzinfo=timezone.utc)   # 09:00 en Bogotá


def iso(dt):
    return dt.isoformat().replace("+00:00", "Z")


def ev(etype, payload, at, eid=None):
    return {"event_id": eid or str(uuid.uuid4()), "event_type": etype, "event_version": 1, "occurred_at": iso(at),
            "aggregate_type": "Ticket", "aggregate_id": payload.get("ticket_id", "x"), "correlation_id": "c",
            "source": "helpdesk-api", "payload": payload, "_routing_key": "x", "_ingested_at": iso(at)}


TEC_A, TEC_B = "aaaaaaaa-0000-0000-0000-000000000001", "bbbbbbbb-0000-0000-0000-000000000002"


def scenario():
    """4 tickets: T1 cerrado (IA acierta, auto), T2 reincidente del mismo equipo, T3 abierto, T4 reabierto y cerrado."""
    E = []

    def created(tid, num, at, equipo="IMP-CEN-01", prio="MEDIA"):
        E.append(ev("TicketCreated", {"ticket_id": tid, "numero": num, "solicitante_id": "u1", "cargo": "Contadora",
                                      "area": "Finanzas", "sede_codigo": "SEDE_CENTRO", "equipo_codigo": equipo, "canal": "WEB",
                                      "impacto": "INDIVIDUAL", "bloquea_trabajo": False, "prioridad_codigo": prio,
                                      "estado_codigo": "PENDIENTE", "adjuntos": 0, "es_demo": False, "creado_en": iso(at)}, at))

    def classify(tid, at, tipo="IMP_ATASCO", cat="IMPRESION", origen="IA", conf=0.85):
        E.append(ev("TicketClassified", {"ticket_id": tid, "categoria_codigo": cat, "tipo_falla_codigo": tipo, "origen": origen,
                                         "confianza": conf, "requiere_revision": conf < 0.75, "es_demo": False,
                                         "clasificado_en": iso(at)}, at))

    def assign(tid, at, tec, modo="AUTO", rec=None):
        E.append(ev("TechnicianAssigned", {"asignacion_id": str(uuid.uuid4()), "ticket_id": tid, "modo": modo,
                                           "tecnico_recomendado_id": rec or tec, "tecnico_asignado_id": tec,
                                           "puntaje_recomendado": 0.8, "puntaje_asignado": 0.8, "pesos_version": "v1",
                                           "es_demo": False, "asignado_en": iso(at)}, at))

    def status(tid, a, b, at):
        E.append(ev("TicketStatusChanged", {"ticket_id": tid, "estado_anterior": a, "estado_nuevo": b, "cambiado_por": "x",
                                            "rol_actor": "TECNICO", "es_demo": False, "cambiado_en": iso(at)}, at))

    def close(tid, at, tec, final, ia):
        status(tid, "EN_CURSO", "FINALIZADA", at)
        E.append(ev("TicketClosed", {"ticket_id": tid, "tecnico_id": tec, "categoria_codigo": "IMPRESION",
                                     "tipo_falla_final": final, "ia_tipo_falla_propuesto": ia, "ia_categoria_propuesta": "IMPRESION",
                                     "reaperturas": 0, "es_demo": False, "finalizado_en": iso(at)}, at))

    def ai(tid, at, tipo, conf):
        E.append({**ev("AiDiagnosisCompleted", {"ticket_id": tid, "trigger": "TICKET_CREATED", "proveedor": "openai",
                                                "modelo": "m", "diagnosticos": [{"origen": "TEXTO", "categoria_codigo": "IMPRESION",
                                                                                 "tipo_falla_codigo": tipo, "confianza": conf,
                                                                                 "concluyente": True, "latencia_ms": 900}],
                                                "combinado": {"categoria_codigo": "IMPRESION", "tipo_falla_codigo": tipo,
                                                              "confianza": conf, "regla": "FUENTE_UNICA_TEXTO"}}, at),
                  "source": "ai-service"})

    h = lambda n: timedelta(hours=n)  # noqa: E731
    # T1: creado, IA acierta, auto-asignado, atendido y cerrado en 5 h
    created("t1", "SOP-000001", T0); ai("t1", T0 + h(0.02), "IMP_ATASCO", 0.85)
    classify("t1", T0 + h(0.02)); assign("t1", T0 + h(0.03), TEC_A)
    status("t1", "PENDIENTE", "EN_CURSO", T0 + h(1)); close("t1", T0 + h(5), TEC_A, "IMP_ATASCO", "IMP_ATASCO")
    E.append(ev("ActivityLogged", {"actividad_id": "a1", "ticket_id": "t1", "tecnico_id": TEC_A, "tipo": "REPARACION",
                                   "minutos": 45, "es_demo": False, "registrado_en": iso(T0 + h(2))}, T0 + h(2)))
    E.append(ev("SurveySubmitted", {"ticket_id": "t1", "calificacion": 5, "tiene_comentario": False, "es_demo": False,
                                    "enviado_en": iso(T0 + h(6))}, T0 + h(6)))
    # T2: mismo equipo y categoría 3 días después del cierre de T1 -> reincidente; IA se equivoca
    t2 = T0 + timedelta(days=3)
    created("t2", "SOP-000002", t2); ai("t2", t2 + h(0.02), "IMP_ERROR", 0.6)
    classify("t2", t2 + h(0.02), tipo="IMP_ERROR", conf=0.6); assign("t2", t2 + h(1), TEC_B, modo="MANUAL", rec=TEC_A)
    status("t2", "PENDIENTE", "EN_CURSO", t2 + h(2)); close("t2", t2 + h(8), TEC_B, "IMP_ATASCO", "IMP_ERROR")
    # T3: abierto, prioridad alta, en curso
    t3 = datetime(2026, 11, 10, 15, 0, tzinfo=timezone.utc)
    created("t3", "SOP-000003", t3, equipo=None, prio="ALTA"); classify("t3", t3, tipo="RED_WIFI", cat="REDES", conf=0.9)
    assign("t3", t3 + h(0.1), TEC_A); status("t3", "PENDIENTE", "EN_CURSO", t3 + h(1))
    # T4: cerrado, reabierto y cerrado otra vez
    t4 = T0 + timedelta(days=1)
    created("t4", "SOP-000004", t4, equipo="PC-CEN-001"); classify("t4", t4, tipo="IMP_ATASCO", conf=0.8)
    assign("t4", t4 + h(0.1), TEC_A); status("t4", "PENDIENTE", "EN_CURSO", t4 + h(1))
    close("t4", t4 + h(3), TEC_A, "IMP_ATASCO", "IMP_ATASCO")
    status("t4", "FINALIZADA", "EN_CURSO", t4 + h(30)); close("t4", t4 + h(33), TEC_A, "IMP_ATASCO", "IMP_ATASCO")
    return E


def snapshots(run_id="r1", ts="2026-11-12T00:00:00Z"):
    rows = {
        "categorias": [{"entity_id": "IMPRESION", "nombre": "Impresión"}, {"entity_id": "REDES", "nombre": "Redes y conectividad"}],
        "tipos_falla": [{"entity_id": "IMP_ATASCO", "nombre": "Atasco de papel", "categoria_codigo": "IMPRESION"},
                        {"entity_id": "IMP_ERROR", "nombre": "Error en impresora", "categoria_codigo": "IMPRESION"},
                        {"entity_id": "RED_WIFI", "nombre": "Wifi", "categoria_codigo": "REDES"}],
        "sedes": [{"entity_id": "SEDE_CENTRO", "nombre": "Sede Centro", "ciudad": "Bogotá", "activo": True}],
        "equipos": [{"entity_id": "IMP-CEN-01", "tipo": "impresora", "marca": "HP", "modelo": "M404", "sede_codigo": "SEDE_CENTRO", "activo": True}],
        "prioridades": [{"entity_id": c, "nombre": c.title(), "peso": i + 1} for i, c in enumerate(["BAJA", "MEDIA", "ALTA", "CRITICA"])],
        "estados": [{"entity_id": c, "nombre": c, "abierto": c in ("PENDIENTE", "EN_CURSO")} for c in ["PENDIENTE", "EN_CURSO", "FINALIZADA", "CANCELADA"]],
        "usuarios": [{"entity_id": "u1", "cargo": "Contadora", "area": "finanzas", "sede_codigo": "SEDE_CENTRO", "rol": "USER", "activo": True}],
        "tecnicos": [{"entity_id": TEC_A, "nombre": "Laura Gómez", "nivel": "SENIOR", "disponible": True, "carga_maxima": 12,
                      "activo": True, "especialidades": "ESP_IMPRESORAS", "categorias": "IMPRESION"},
                     {"entity_id": TEC_B, "nombre": "Julián Pérez", "nivel": "JUNIOR", "disponible": True, "carga_maxima": 8,
                      "activo": True, "especialidades": "ESP_IMPRESORAS", "categorias": "IMPRESION"}],
    }
    return {e: [{**r, "_snapshot_run_id": run_id, "_snapshot_ts": ts} for r in rs] for e, rs in rows.items()}


@pytest.fixture(scope="module")
def lake(tmp_path_factory):
    base = tmp_path_factory.mktemp("lake")
    os.environ["LAKE_LOCAL_DIR"] = str(base)
    import importlib
    from lakehouse import config as cfgmod
    importlib.reload(cfgmod)
    cfg = cfgmod.LakeConfig()
    from lakehouse.io import LakeIO, LandingSource, build_spark
    spark = build_spark(cfg, app="test")
    io, landing = LakeIO(spark, cfg), LandingSource(cfg)
    dt = datetime.now(timezone.utc).date().isoformat()
    events = scenario()
    landing.write(f"landing/events/mix/dt={dt}/lote1.jsonl", events)
    landing.write(f"landing/events/mix/dt={dt}/lote2.jsonl", events[:5])  # reentrega: duplicados
    for entity, rows in snapshots().items():
        landing.write(f"landing/snapshots/{entity}/dt={dt}/r1.jsonl", rows)
    # Una segunda foto con el técnico B ascendido -> nueva versión SCD2
    s2 = snapshots("r2", "2026-11-12T01:00:00Z")["tecnicos"]
    s2[1]["nivel"] = "SEMI_SENIOR"
    landing.write(f"landing/snapshots/tecnicos/dt={dt}/r2.jsonl", s2)
    landing.write(f"landing/events/roto/dt={dt}/malo.jsonl", [{"sin": "event_id"}])
    yield spark, io, landing, len(events)
    spark.stop()


def test_pipeline_completo(lake):
    from lakehouse import bronze, gold, silver
    from lakehouse.ops import step
    spark, io, landing, n_events = lake

    with step(io, "run1", "bronze") as ctx:
        bronze.run(io, landing, ctx, days=3)
    assert io.read("bronze.domain_events").count() == n_events      # sin duplicados
    assert io.read("ops.quarantine_rows").count() == 1               # la línea sin event_id
    with step(io, "run2", "bronze") as ctx:                           # reejecución idempotente
        bronze.run(io, landing, ctx, days=3)
    assert io.read("bronze.domain_events").count() == n_events

    with step(io, "run1", "silver") as ctx:
        silver.build(io, ctx)
    t = {r["ticket_id"]: r for r in io.read("silver.tickets").collect()}
    assert t["t1"]["estado_codigo"] == "FINALIZADA" and t["t1"]["tipo_falla_codigo"] == "IMP_ATASCO"
    assert t["t3"]["estado_codigo"] == "EN_CURSO" and t["t3"]["finalizado_en"] is None
    assert t["t4"]["reaperturas"] == 1
    assert t["t2"]["requirio_revision"] is True and t["t2"]["asignado_por_ia"] is False
    assert t["t1"]["solicitante_hash"] != "u1" and len(t["t1"]["solicitante_hash"]) == 64   # seudonimizado
    tec = io.read("silver.technicians").where("tecnico_id = 'bbbbbbbb-0000-0000-0000-000000000002'").collect()
    assert sorted(r["nivel"] for r in tec) == ["JUNIOR", "SEMI_SENIOR"]                    # SCD2
    assert [r["nivel"] for r in tec if r["es_actual"]] == ["SEMI_SENIOR"]

    with step(io, "run1", "gold") as ctx:
        gold.build(io, ctx, today=TODAY)
    f = {r["ticket_id"]: r for r in io.read("gold.fact_tickets").collect()}
    assert f["t1"]["min_resolucion"] == 300.0                       # 5 h
    assert f["t1"]["min_hasta_atencion"] == pytest.approx(58.2, abs=0.1)
    assert f["t2"]["es_reincidente"] is True and f["t1"]["es_reincidente"] is False
    assert f["t1"]["ia_acerto"] is True and f["t2"]["ia_acerto"] is False and f["t3"]["ia_acerto"] is None
    assert f["t1"]["asignacion_efectiva"] is True and f["t2"]["asignacion_efectiva"] is None
    assert f["t1"]["fecha_creacion"] == date(2026, 11, 2)            # fecha local de Bogotá
    assert f["t1"]["calificacion"] == 5
    assert f["t1"]["tecnico_key"] is not None

    diag = {r["ticket_id"]: r["resultado"] for r in io.read("gold.fact_diagnosticos_ia").collect()}
    assert diag == {"t1": "ACERTADO", "t2": "CORREGIDO"}

    snap = io.read("gold.fact_snapshot_diario")
    # T3 abierto desde el 10-nov: aparece en las fotos del 10, 11 y 12
    assert snap.where("ticket_id = 't3'").count() == 3
    # T1 se cerró el mismo día: no queda abierto al final de ningún día
    assert snap.where("ticket_id = 't1'").count() == 0

    rend = {(r["tecnico_id"], r["categoria_codigo"]): r for r in io.read("gold.agg_rendimiento_tecnico").collect()}
    a = rend[(TEC_A, "IMPRESION")]
    assert a["resueltos_90d"] == 2 and a["desempeno"] == 0.5 and a["nombre"] == "Laura Gómez"   # T4 tuvo reapertura

    fechas = io.read("gold.dim_fecha").where("fecha = date'2026-11-02'").first()
    assert fechas["nombre_dia"] == "lunes" and fechas["fecha_key"] == 20261102
    runs = {r["step"]: r["status"] for r in io.read("ops.pipeline_runs").collect()}
    assert runs == {"bronze": "OK", "silver": "OK", "gold": "OK"}
    assert io.read("ops.dq_results").where("NOT passed AND severity = 'critical'").count() == 0
    assert io.read("ops.lineage").where("target_table = 'gold.fact_tickets'").count() >= 1


def test_regla_critica_detiene_el_paso(lake):
    from pyspark.sql import Row

    from lakehouse.ops import StepContext
    from lakehouse.quality import QualityError, QualityGate
    spark, io, _, _ = lake
    rules = {"silver.demo": {"row_rules": [{"name": "estado", "expr": "estado IN ('A','B')", "severity": "critical"}],
                             "table_rules": [{"name": "max", "type": "max_rejected_ratio", "value": 0.2, "severity": "critical"}]}}
    gate = QualityGate(io, StepContext(io, "rq", "silver"), rules)
    ok = gate.apply("silver.demo", spark.createDataFrame([Row(estado="A")] * 9 + [Row(estado="X")]))
    assert ok.count() == 9                                            # 10 % en cuarentena: se permite
    with pytest.raises(QualityError):
        gate.apply("silver.demo", spark.createDataFrame([Row(estado="A"), Row(estado="X")]))   # 50 %: se detiene
    _ = json  # noqa


def test_bronze_incremental_y_por_bloques(lake, monkeypatch):
    """Una reejecución no vuelve a leer archivos ya cargados y los nuevos se escriben en bloques acotados."""
    from lakehouse import bronze
    from lakehouse.ops import step
    spark, io, landing, _ = lake
    antes = io.read("bronze.domain_events").count()

    leidos = []
    original = landing.read_lines
    monkeypatch.setattr(landing, "read_lines", lambda k: leidos.append(k) or original(k))
    with step(io, "run3", "bronze") as ctx:
        assert bronze.load_events(io, landing, ctx, days=3) == 0
    assert not any(k.endswith("lote1.jsonl") for k in leidos)        # ya estaba en Bronze: no se relee

    dt = datetime.now(timezone.utc).date().isoformat()
    for i in range(3):
        nuevos = [ev("SurveySubmitted", {"ticket_id": f"n{i}{j}", "calificacion": 5}, T0) for j in range(3)]
        landing.write(f"landing/events/mix/dt={dt}/nuevo{i}.jsonl", nuevos)
    bloques = []
    agregar = bronze._append_events
    monkeypatch.setattr(bronze, "CHUNK_EVENTS", 3)
    monkeypatch.setattr(bronze, "_append_events", lambda io_, rows: bloques.append(len(rows)) or agregar(io_, rows))
    with step(io, "run4", "bronze") as ctx:
        assert bronze.load_events(io, landing, ctx, days=3) == 9
    assert bloques[-3:] == [3, 3, 3]    # (antes puede venir la reentrega solo con duplicados: se lee y se descarta)
    assert io.read("bronze.domain_events").count() == antes + 9


def test_purga_de_tickets(lake):
    """La purga quita de Bronze y de Landing todos los eventos de los tickets >= número indicado."""
    import json as _json
    from lakehouse import purge, silver
    from lakehouse.ops import step
    spark, io, landing, _ = lake
    ids = purge.ticket_ids(io, "SOP-000004")
    assert ids == {"t4"}
    with step(io, "rp", "purge") as ctx:
        r = purge.purgar(io, landing, ctx, "SOP-000004")
    assert r["tickets"] == 1 and r["eventos_bronze_borrados"] > 0
    ev = io.read("bronze.domain_events")
    assert ev.where("aggregate_id = 't4' OR get_json_object(payload, '$.ticket_id') = 't4'").count() == 0
    for key in landing.list("landing/events/"):                      # tampoco queda en Landing
        for line in landing.read_lines(key):
            try:
                e = _json.loads(line)
            except ValueError:
                continue
            assert e.get("aggregate_id") != "t4" and (e.get("payload") or {}).get("ticket_id") != "t4"
    with step(io, "rp", "silver") as ctx:
        silver.build(io, ctx)
    assert {r["ticket_id"] for r in io.read("silver.tickets").collect()} == {"t1", "t2", "t3"}
    with step(io, "rp2", "purge") as ctx:                             # idempotente
        assert purge.purgar(io, landing, ctx, "SOP-000004")["tickets"] == 0
