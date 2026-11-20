package com.helpdesk.api.analytics;

import com.helpdesk.api.config.AppProperties;
import com.helpdesk.api.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Acceso de SOLO LECTURA a la capa Gold del Data Lake a través de Trino.
 * El usuario "analytics" solo tiene SELECT sobre los esquemas gold y ops (rules.json).
 * Todas las consultas usan parámetros enlazados (sin concatenar valores del usuario).
 */
@Component
public class TrinoGateway {

    private final AppProperties.Trino cfg;

    public TrinoGateway(AppProperties props) {
        this.cfg = props.trino();
    }

    public List<Map<String, Object>> query(String sql, List<?> params) {
        Properties p = new Properties();
        p.setProperty("user", cfg.user());
        try (Connection c = DriverManager.getConnection(cfg.url(), p);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            for (int i = 0; i < params.size(); i++) {
                Object v = params.get(i);
                // Las fechas viajan como texto ISO (yyyy-MM-dd) y la consulta hace CAST(? AS date): setDate del
                // controlador JDBC de Trino no filtraba correctamente y los tableros salían en cero.
                if (v instanceof LocalDate d) ps.setString(i + 1, d.toString());
                else ps.setObject(i + 1, v);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Object val = rs.getObject(i);
                        if (val instanceof Date d) val = d.toLocalDate().toString();
                        else if (val instanceof java.sql.Timestamp t) val = t.toInstant().toString();
                        row.put(md.getColumnLabel(i), val);
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("does not exist") || msg.contains("not found")) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LAKE_SIN_DATOS",
                        "La capa Gold aún no tiene datos. Ejecuta el pipeline del Data Lake (o espera la próxima ejecución).");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LAKE_NO_DISPONIBLE",
                    "El Data Lake no está disponible en este momento: " + recortar(msg));
        }
    }

    private static String recortar(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
