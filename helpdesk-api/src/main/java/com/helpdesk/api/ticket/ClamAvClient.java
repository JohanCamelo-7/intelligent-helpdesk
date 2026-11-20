package com.helpdesk.api.ticket;

import com.helpdesk.api.config.AppProperties;
import com.helpdesk.api.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Cliente mínimo del protocolo INSTREAM de clamd. Solo actúa si CLAMAV_ENABLED=true. */
@Component
public class ClamAvClient {

    private static final Logger log = LoggerFactory.getLogger(ClamAvClient.class);
    private static final int CHUNK = 8192;
    private final AppProperties.ClamAv cfg;

    public ClamAvClient(AppProperties props) {
        this.cfg = props.clamav();
    }

    public boolean enabled() { return cfg != null && cfg.enabled(); }

    /** @return true si el archivo está limpio. Lanza 503 si el antivirus está activado pero no responde. */
    public boolean isClean(byte[] data) {
        if (!enabled()) return true;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(cfg.host(), cfg.port()), 3000);
            socket.setSoTimeout(15000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int i = 0; i < data.length; i += CHUNK) {
                int len = Math.min(CHUNK, data.length - i);
                out.writeInt(len);
                out.write(data, i, len);
            }
            out.writeInt(0);
            out.flush();
            InputStream in = socket.getInputStream();
            String reply = new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
            if (reply.endsWith("OK")) return true;
            log.warn("ClamAV rechazó un archivo: {}", reply);
            return false;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ANTIVIRUS_NO_DISPONIBLE",
                    "El antivirus no está disponible. Intenta de nuevo en unos minutos.");
        }
    }
}
