package com.smartfirehub.dataimport.service;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.smartfirehub.dataimport.dto.ParseOptions;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
public class FileParserService {

    // -----------------------------------------------------------------------
    // Public API — no-options variants (backward-compatible, use defaults)
    // -----------------------------------------------------------------------

    public List<Map<String, String>> parseCSV(InputStream inputStream) throws Exception {
        return parseCSV(inputStream, ParseOptions.defaults());
    }

    public List<Map<String, String>> parse(byte[] fileData, String fileType) throws Exception {
        return parse(fileData, fileType, ParseOptions.defaults());
    }

    public List<String> parseHeaders(byte[] fileData, String fileType) throws Exception {
        return parseHeaders(fileData, fileType, ParseOptions.defaults());
    }

    public List<Map<String, String>> parseSampleRows(byte[] fileData, String fileType, int maxRows) throws Exception {
        return parseSampleRows(fileData, fileType, maxRows, ParseOptions.defaults());
    }

    public int countRows(byte[] fileData, String fileType) throws Exception {
        return countRows(fileData, fileType, ParseOptions.defaults());
    }

    // -----------------------------------------------------------------------
    // Public API — with ParseOptions
    // -----------------------------------------------------------------------

    public List<Map<String, String>> parseCSV(InputStream inputStream, ParseOptions opts) throws Exception {
        try (CSVReader reader = buildCsvReader(inputStream, opts)) {
            // skip rows before header
            for (int s = 0; s < opts.skipRows(); s++) {
                if (reader.readNext() == null) break;
            }

            List<String[]> allRows = reader.readAll();
            if (allRows.isEmpty()) {
                return Collections.emptyList();
            }

            String[] headers;
            int dataStart;
            if (opts.hasHeader()) {
                headers = allRows.get(0);
                dataStart = 1;
            } else {
                // generate column_1, column_2, ... based on first row width
                int colCount = allRows.get(0).length;
                headers = new String[colCount];
                for (int i = 0; i < colCount; i++) {
                    headers[i] = "column_" + (i + 1);
                }
                dataStart = 0;
            }

            List<Map<String, String>> result = new ArrayList<>();
            for (int i = dataStart; i < allRows.size(); i++) {
                String[] row = allRows.get(i);
                Map<String, String> rowMap = new HashMap<>();
                for (int j = 0; j < headers.length && j < row.length; j++) {
                    rowMap.put(headers[j], row[j]);
                }
                result.add(rowMap);
            }
            return result;
        }
    }

    public List<Map<String, String>> parse(byte[] fileData, String fileType, ParseOptions opts) throws Exception {
        InputStream inputStream = new ByteArrayInputStream(fileData);
        return switch (fileType.toLowerCase()) {
            case "csv" -> parseCSV(inputStream, opts);
            case "xlsx", "xls" -> parseExcel(inputStream);
            default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
        };
    }

    public List<String> parseHeaders(byte[] fileData, String fileType, ParseOptions opts) throws Exception {
        InputStream inputStream = new ByteArrayInputStream(fileData);
        return switch (fileType.toLowerCase()) {
            case "csv" -> parseHeadersCSV(inputStream, opts);
            case "xlsx", "xls" -> parseHeadersExcel(inputStream);
            default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
        };
    }

    public List<Map<String, String>> parseSampleRows(byte[] fileData, String fileType, int maxRows, ParseOptions opts) throws Exception {
        InputStream inputStream = new ByteArrayInputStream(fileData);
        return switch (fileType.toLowerCase()) {
            case "csv" -> parseSampleRowsCSV(inputStream, maxRows, opts);
            case "xlsx", "xls" -> parseSampleRowsExcel(inputStream, maxRows);
            default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
        };
    }

    public int countRows(byte[] fileData, String fileType, ParseOptions opts) throws Exception {
        InputStream inputStream = new ByteArrayInputStream(fileData);
        return switch (fileType.toLowerCase()) {
            case "csv" -> countRowsCSV(inputStream, opts);
            case "xlsx", "xls" -> countRowsExcel(inputStream);
            default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
        };
    }

    // -----------------------------------------------------------------------
    // Excel (no ParseOptions — Excel handles encoding internally)
    // -----------------------------------------------------------------------

    public List<Map<String, String>> parseExcel(InputStream inputStream) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            if (!rowIterator.hasNext()) {
                return Collections.emptyList();
            }

            Row headerRow = rowIterator.next();
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(getCellValueAsString(cell));
            }

            List<Map<String, String>> result = new ArrayList<>();
            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                Map<String, String> rowMap = new HashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = row.getCell(i);
                    String value = cell != null ? getCellValueAsString(cell) : "";
                    rowMap.put(headers.get(i), value);
                }
                result.add(rowMap);
            }
            return result;
        }
    }

    // -----------------------------------------------------------------------
    // Private CSV helpers with ParseOptions
    // -----------------------------------------------------------------------

    private CSVReader buildCsvReader(InputStream inputStream, ParseOptions opts) {
        char sep = opts.delimiter().charAt(0);
        Charset charset = Charset.forName(opts.encoding());
        CSVParser parser = new CSVParserBuilder().withSeparator(sep).build();
        return new CSVReaderBuilder(new InputStreamReader(inputStream, charset))
                .withCSVParser(parser)
                .build();
    }

    private List<String> parseHeadersCSV(InputStream inputStream, ParseOptions opts) throws Exception {
        try (CSVReader reader = buildCsvReader(inputStream, opts)) {
            // skip rows before header
            for (int s = 0; s < opts.skipRows(); s++) {
                if (reader.readNext() == null) return Collections.emptyList();
            }

            if (opts.hasHeader()) {
                String[] headers = reader.readNext();
                return headers != null ? Arrays.asList(headers) : Collections.emptyList();
            } else {
                // peek at first data row to determine column count
                String[] firstRow = reader.readNext();
                if (firstRow == null) return Collections.emptyList();
                List<String> headers = new ArrayList<>();
                for (int i = 0; i < firstRow.length; i++) {
                    headers.add("column_" + (i + 1));
                }
                return headers;
            }
        }
    }

    private List<Map<String, String>> parseSampleRowsCSV(InputStream inputStream, int maxRows, ParseOptions opts) throws Exception {
        try (CSVReader reader = buildCsvReader(inputStream, opts)) {
            for (int s = 0; s < opts.skipRows(); s++) {
                if (reader.readNext() == null) return Collections.emptyList();
            }

            List<String[]> allRows = reader.readAll();
            if (allRows.isEmpty()) {
                return Collections.emptyList();
            }

            String[] headers;
            int dataStart;
            if (opts.hasHeader()) {
                headers = allRows.get(0);
                dataStart = 1;
            } else {
                int colCount = allRows.get(0).length;
                headers = new String[colCount];
                for (int i = 0; i < colCount; i++) headers[i] = "column_" + (i + 1);
                dataStart = 0;
            }

            List<Map<String, String>> result = new ArrayList<>();
            int limit = Math.min(allRows.size() - dataStart, maxRows);
            for (int i = dataStart; i < dataStart + limit; i++) {
                String[] row = allRows.get(i);
                Map<String, String> rowMap = new HashMap<>();
                for (int j = 0; j < headers.length && j < row.length; j++) {
                    rowMap.put(headers[j], row[j]);
                }
                result.add(rowMap);
            }
            return result;
        }
    }

    private int countRowsCSV(InputStream inputStream, ParseOptions opts) throws Exception {
        try (CSVReader reader = buildCsvReader(inputStream, opts)) {
            for (int s = 0; s < opts.skipRows(); s++) {
                if (reader.readNext() == null) return 0;
            }

            List<String[]> allRows = reader.readAll();
            if (allRows.isEmpty()) return 0;
            // subtract header row if present
            return opts.hasHeader() ? Math.max(0, allRows.size() - 1) : allRows.size();
        }
    }

    // -----------------------------------------------------------------------
    // Private Excel helpers (no ParseOptions)
    // -----------------------------------------------------------------------

    private List<String> parseHeadersExcel(InputStream inputStream) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return Collections.emptyList();
            }
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(getCellValueAsString(cell));
            }
            return headers;
        }
    }

    private List<Map<String, String>> parseSampleRowsExcel(InputStream inputStream, int maxRows) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            if (!rowIterator.hasNext()) {
                return Collections.emptyList();
            }

            Row headerRow = rowIterator.next();
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(getCellValueAsString(cell));
            }

            List<Map<String, String>> result = new ArrayList<>();
            int count = 0;
            while (rowIterator.hasNext() && count < maxRows) {
                Row row = rowIterator.next();
                Map<String, String> rowMap = new HashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = row.getCell(i);
                    String value = cell != null ? getCellValueAsString(cell) : "";
                    rowMap.put(headers.get(i), value);
                }
                result.add(rowMap);
                count++;
            }
            return result;
        }
    }

    private int countRowsExcel(InputStream inputStream) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            return Math.max(0, lastRowNum);
        }
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    Date date = cell.getDateCellValue();
                    LocalDateTime ldt = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                    yield ldt.toString();
                } else {
                    double numericValue = cell.getNumericCellValue();
                    if (numericValue == Math.floor(numericValue)) {
                        yield String.valueOf((long) numericValue);
                    } else {
                        yield String.valueOf(numericValue);
                    }
                }
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    yield cell.getStringCellValue();
                }
            }
            case BLANK -> "";
            default -> "";
        };
    }
}
