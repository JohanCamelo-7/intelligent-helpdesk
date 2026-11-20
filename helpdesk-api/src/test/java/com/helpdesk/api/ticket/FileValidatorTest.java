package com.helpdesk.api.ticket;

import com.helpdesk.api.shared.ApiException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileValidatorTest {

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void aceptaPngRealYLeeDimensiones() throws IOException {
        var r = FileValidator.validar(png(40, 30), "foto.png", 5_000_000, 40_000_000);
        assertThat(r.mime()).isEqualTo("image/png");
        assertThat(r.ancho()).isEqualTo(40);
        assertThat(r.alto()).isEqualTo(30);
    }

    @Test
    void rechazaArchivoDisfrazadoDeImagen() {
        byte[] script = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> FileValidator.validar(script, "foto.jpg", 5_000_000, 40_000_000))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("FORMATO_NO_PERMITIDO"));
    }

    @Test
    void rechazaPorTamanoYResolucion() throws IOException {
        byte[] img = png(100, 100);
        assertThatThrownBy(() -> FileValidator.validar(img, "a.png", 10, 40_000_000))
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ARCHIVO_GRANDE"));
        assertThatThrownBy(() -> FileValidator.validar(img, "a.png", 5_000_000, 5_000))
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("IMAGEN_ENORME"));
    }

    @Test
    void rechazaVacio() {
        assertThatThrownBy(() -> FileValidator.validar(new byte[0], "x.png", 10, 10))
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ARCHIVO_VACIO"));
    }
}
