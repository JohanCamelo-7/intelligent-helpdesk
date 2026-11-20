package com.helpdesk.api.assignment;

import com.helpdesk.api.technician.Nivel;
import com.helpdesk.api.ticket.Prioridad;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentScorerTest {

    private static final AssignmentScorer.Weights W = new AssignmentScorer.Weights(0.40, 0.20, 0.25, 0.15, "v1");

    private static AssignmentScorer.Candidate cand(String nombre, Nivel nivel, boolean exacta, boolean categoria,
                                                   int carga, int max, Double d) {
        return new AssignmentScorer.Candidate(UUID.nameUUIDFromBytes(nombre.getBytes()), nombre, nivel, exacta, categoria,
                carga, max, d, true, true);
    }

    @Test
    void prefiereEspecialidadExactaSobreMenorCarga() {
        var experto = cand("Experto", Nivel.SEMI_SENIOR, true, true, 6, 12, null);
        var libre = cand("Libre", Nivel.SEMI_SENIOR, false, true, 0, 12, null);
        var r = AssignmentScorer.evaluar(Prioridad.MEDIA, 0.9, List.of(libre, experto), W, 0.75, 0.60);
        assertThat(r.decision()).isEqualTo(AssignmentScorer.Decision.AUTO);
        assertThat(r.mejor().nombre()).isEqualTo("Experto");
        // E=1 (0.40) + X=0.66 (0.132) + (1-0.5)·0.25 (0.125) + D=0.5 (0.075) = 0.732
        assertThat(r.mejor().puntaje()).isEqualTo(0.732);
    }

    @Test
    void excluyeNoDisponiblesSinCategoriaYSaturados() {
        var noCat = cand("SinCategoria", Nivel.SENIOR, false, false, 0, 12, null);
        var lleno = cand("Lleno", Nivel.SENIOR, true, true, 12, 12, null);
        var ausente = new AssignmentScorer.Candidate(UUID.randomUUID(), "Ausente", Nivel.SENIOR, true, true, 0, 12, null, false, true);
        var r = AssignmentScorer.evaluar(Prioridad.MEDIA, 0.9, List.of(noCat, lleno, ausente), W, 0.75, 0.60);
        assertThat(r.ranking()).isEmpty();
        assertThat(r.excluidos()).hasSize(3);
        assertThat(r.decision()).isEqualTo(AssignmentScorer.Decision.REVISION);
    }

    @Test
    void prioridadCriticaExcluyeJuniorYPesaMasLaExperiencia() {
        var junior = cand("Junior", Nivel.JUNIOR, true, true, 0, 12, null);
        var senior = cand("Senior", Nivel.SENIOR, false, true, 8, 12, null);
        var r = AssignmentScorer.evaluar(Prioridad.CRITICA, 0.95, List.of(junior, senior), W, 0.75, 0.60);
        assertThat(r.ranking()).extracting(AssignmentScorer.Breakdown::nombre).containsExactly("Senior");
        assertThat(r.pesos().experiencia()).isEqualTo(0.25);
        assertThat(r.pesos().carga()).isEqualTo(0.20);
    }

    @Test
    void confianzaBajaNuncaAsignaAutomaticamente() {
        var c = cand("Ok", Nivel.SENIOR, true, true, 0, 12, 1.0);
        var r = AssignmentScorer.evaluar(Prioridad.BAJA, 0.40, List.of(c), W, 0.75, 0.60);
        assertThat(r.decision()).isEqualTo(AssignmentScorer.Decision.REVISION);
        assertThat(r.motivo()).contains("confianza");
        assertThat(r.mejor()).isNotNull(); // la recomendación se conserva para el ADMIN
    }

    @Test
    void puntajeInsuficienteVaARevision() {
        var debil = cand("Debil", Nivel.JUNIOR, false, true, 7, 8, 0.0);
        var r = AssignmentScorer.evaluar(Prioridad.MEDIA, 0.9, List.of(debil), W, 0.75, 0.60);
        assertThat(r.decision()).isEqualTo(AssignmentScorer.Decision.REVISION);
    }

    @Test
    void empateSeResuelvePorMenorCarga() {
        var a = cand("A", Nivel.SENIOR, true, true, 4, 8, null);
        var b = cand("B", Nivel.SENIOR, true, true, 2, 4, null);
        var r = AssignmentScorer.evaluar(Prioridad.MEDIA, 0.9, List.of(a, b), W, 0.75, 0.60);
        assertThat(r.ranking().get(0).puntaje()).isEqualTo(r.ranking().get(1).puntaje());
        assertThat(r.mejor().nombre()).isEqualTo("B");
    }

    @Test
    void laExplicacionNombraLosCriterios() {
        var c = cand("Exp", Nivel.SENIOR, true, true, 3, 12, 0.85);
        var r = AssignmentScorer.evaluar(Prioridad.MEDIA, 0.9, List.of(c), W, 0.75, 0.60);
        assertThat(r.mejor().explicacion()).contains("especialidad exacta", "nivel senior", "carga 3 de 12", "85%");
    }
}
