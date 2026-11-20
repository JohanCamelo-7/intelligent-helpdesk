package com.helpdesk.api.ticket;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityRulesTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "INDIVIDUAL, false, null,      BAJA",
            "INDIVIDUAL, true,  null,      MEDIA",
            "AREA,       false, null,      MEDIA",
            "AREA,       true,  null,      ALTA",
            "SEDE,       false, null,      ALTA",
            "SEDE,       true,  null,      CRITICA",
            "AREA,       false, REDES,     ALTA",
            "SEDE,       true,  ACCESOS,   CRITICA",
            "INDIVIDUAL, false, REDES,     BAJA",
            "AREA,       false, IMPRESION, MEDIA"})
    void estimaSegunReglas(Impacto impacto, boolean bloquea, String categoria, Prioridad esperada) {
        assertThat(PriorityRules.estimar(impacto, bloquea, categoria)).isEqualTo(esperada);
    }
}
