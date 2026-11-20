package com.helpdesk.api.ticket;

import com.helpdesk.api.shared.ApiException;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * Validación de imágenes por su contenido real (firma del archivo), no por la extensión
 * ni por el Content-Type que envía el navegador.
 */
public final class FileValidator {

    public static final Map<String, String> PERMITIDOS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private static final Tika TIKA = new Tika();

    private FileValidator() {}

    public record Resultado(String mime, String extension, Integer ancho, Integer alto) {}

    public static Resultado validar(byte[] contenido, String nombre, long maxBytes, long maxPixeles) {
        if (contenido == null || contenido.length == 0) {
            throw ApiException.badRequest("ARCHIVO_VACIO", "El archivo está vacío");
        }
        if (contenido.length > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ARCHIVO_GRANDE",
                    "La imagen supera el tamaño máximo de " + (maxBytes / 1024 / 1024) + " MB");
        }
        String mime = TIKA.detect(contenido);
        String ext = PERMITIDOS.get(mime);
        if (ext == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FORMATO_NO_PERMITIDO",
                    "Solo se permiten imágenes JPG, PNG o WEBP (" + safe(nombre) + " es " + mime + ")");
        }
        Integer ancho = null, alto = null;
        if (!"image/webp".equals(mime)) {
            int[] dims = dimensiones(contenido);
            if (dims == null) {
                throw ApiException.badRequest("IMAGEN_CORRUPTA", "No se pudo leer la imagen " + safe(nombre));
            }
            ancho = dims[0];
            alto = dims[1];
            if ((long) ancho * alto > maxPixeles) {
                throw ApiException.badRequest("IMAGEN_ENORME", "La resolución de la imagen es demasiado grande");
            }
        }
        return new Resultado(mime, ext, ancho, alto);
    }

    /** Lee solo la cabecera para obtener dimensiones sin decodificar la imagen completa. */
    private static int[] dimensiones(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String safe(String nombre) {
        if (nombre == null) return "el archivo";
        String n = nombre.replaceAll("[^\\p{L}\\p{N}._ -]", "");
        return n.length() > 60 ? n.substring(0, 60) : n;
    }
}
