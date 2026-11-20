package com.helpdesk.api.analytics;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Escribe un reporte (varias secciones tabulares) en CSV, XLSX o PDF. */
public final class ReportWriter {

    private ReportWriter() {}

    public record Seccion(String titulo, List<String> columnas, List<List<Object>> filas) {
        public static Seccion de(String titulo, List<Map<String, Object>> rows) {
            List<String> cols = rows.isEmpty() ? List.of("(sin datos)") : new ArrayList<>(rows.get(0).keySet());
            List<List<Object>> filas = rows.stream().map(r -> cols.stream().map(r::get).toList()).toList();
            return new Seccion(titulo, cols, filas);
        }
    }

    public record Reporte(String titulo, List<String> notas, List<Seccion> secciones) {}

    public static byte[] escribir(Reporte r, String formato) {
        try {
            return switch (formato) {
                case "CSV" -> csv(r);
                case "XLSX" -> xlsx(r);
                case "PDF" -> pdf(r);
                default -> throw new IllegalArgumentException("Formato no soportado: " + formato);
            };
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el reporte", e);
        }
    }

    static byte[] csv(Reporte r) {
        StringBuilder sb = new StringBuilder("﻿"); // BOM para que Excel abra UTF-8 correctamente
        sb.append(esc(r.titulo())).append('\n');
        r.notas().forEach(n -> sb.append(esc(n)).append('\n'));
        for (Seccion s : r.secciones()) {
            sb.append('\n').append(esc(s.titulo())).append('\n');
            sb.append(String.join(";", s.columnas().stream().map(ReportWriter::esc).toList())).append('\n');
            for (List<Object> fila : s.filas()) {
                sb.append(String.join(";", fila.stream().map(v -> esc(texto(v))).toList())).append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] xlsx(Reporte r) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle header = wb.createCellStyle();
            XSSFFont bold = wb.createFont();
            bold.setBold(true);
            header.setFont(bold);

            Sheet info = wb.createSheet("Información");
            int i = 0;
            info.createRow(i++).createCell(0).setCellValue(r.titulo());
            for (String n : r.notas()) info.createRow(i++).createCell(0).setCellValue(n);
            info.setColumnWidth(0, 120 * 256);

            int n = 1;
            for (Seccion s : r.secciones()) {
                String nombre = (n++ + " " + s.titulo()).replaceAll("[\\\\/?*\\[\\]:]", " ");
                Sheet sh = wb.createSheet(nombre.length() > 31 ? nombre.substring(0, 31) : nombre);
                Row h = sh.createRow(0);
                for (int c = 0; c < s.columnas().size(); c++) {
                    Cell cell = h.createCell(c);
                    cell.setCellValue(s.columnas().get(c));
                    cell.setCellStyle(header);
                }
                int rowIdx = 1;
                for (List<Object> fila : s.filas()) {
                    Row row = sh.createRow(rowIdx++);
                    for (int c = 0; c < fila.size(); c++) {
                        Object v = fila.get(c);
                        Cell cell = row.createCell(c);
                        if (v instanceof Number num) cell.setCellValue(num.doubleValue());
                        else if (v instanceof Boolean b) cell.setCellValue(b ? "Sí" : "No");
                        else cell.setCellValue(texto(v));
                    }
                }
                for (int c = 0; c < s.columnas().size(); c++) sh.setColumnWidth(c, 20 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    static byte[] pdf(Reporte r) {
        try {
            return pdfInterno(r);
        } catch (Exception e) {   // DocumentException puede ser comprobada según la versión de OpenPDF
            throw new IllegalStateException("No se pudo generar el PDF", e);
        }
    }

    private static byte[] pdfInterno(Reporte r) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font titulo = new Font(Font.HELVETICA, 15, Font.BOLD);
        Font sub = new Font(Font.HELVETICA, 11, Font.BOLD);
        Font nota = new Font(Font.HELVETICA, 8, Font.ITALIC, Color.DARK_GRAY);
        Font celda = new Font(Font.HELVETICA, 7);
        Font cab = new Font(Font.HELVETICA, 7, Font.BOLD, Color.WHITE);
        doc.add(new Paragraph(r.titulo(), titulo));
        for (String n : r.notas()) doc.add(new Paragraph(n, nota));
        for (Seccion s : r.secciones()) {
            Paragraph p = new Paragraph(s.titulo(), sub);
            p.setSpacingBefore(10);
            p.setSpacingAfter(4);
            doc.add(p);
            PdfPTable t = new PdfPTable(Math.max(1, s.columnas().size()));
            t.setWidthPercentage(100);
            for (String c : s.columnas()) {
                PdfPCell cell = new PdfPCell(new Phrase(c, cab));
                cell.setBackgroundColor(new Color(40, 70, 110));
                t.addCell(cell);
            }
            int max = Math.min(s.filas().size(), 2000);
            for (int i = 0; i < max; i++) {
                for (Object v : s.filas().get(i)) {
                    PdfPCell cell = new PdfPCell(new Phrase(texto(v), celda));
                    if (v instanceof Number) cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    t.addCell(cell);
                }
            }
            doc.add(t);
            if (s.filas().size() > max) doc.add(new Paragraph("Se muestran 2000 de " + s.filas().size() + " filas; usa CSV o Excel para el detalle completo.", nota));
        }
        doc.close();
        return out.toByteArray();
    }

    static String texto(Object v) {
        if (v == null) return "";
        if (v instanceof Double d) return d.isNaN() ? "" : String.format(java.util.Locale.ROOT, "%.2f", d);
        if (v instanceof Boolean b) return b ? "Sí" : "No";
        return v.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        String v = s;
        // Evita inyección de fórmulas al abrir el CSV en Excel.
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) v = "'" + v;
        if (v.contains(";") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
}
