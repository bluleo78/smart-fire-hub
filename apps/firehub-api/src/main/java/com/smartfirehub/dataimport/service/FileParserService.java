package com.smartfirehub.dataimport.service;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
public class FileParserService {

    public List<Map<String, String>> parseCSV(InputStream inputStream) throws Exception {
        try (CSVReader reader = new CSVReaderBuilder(new InputStreamReader(inputStream, StandardCharsets.UTF_8)).build()) {
            List<String[]> allRows = reader.readAll();
            if (allRows.isEmpty()) {
                return Collections.emptyList();
            }

            String[] headers = allRows.get(0);
            List<Map<String, String>> result = new ArrayList<>();

            for (int i = 1; i < allRows.size(); i++) {
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

    public List<Map<String, String>> parseExcel(InputStream inputStream) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            if (!rowIterator.hasNext()) {
                return Collections.emptyList();
            }

            // Read header row
            Row headerRow = rowIterator.next();
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(getCellValueAsString(cell));
            }

            // Read data rows
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

    public List<Map<String, String>> parse(byte[] fileData, String fileType) throws Exception {
        InputStream inputStream = new ByteArrayInputStream(fileData);

        return switch (fileType.toLowerCase()) {
            case "csv" -> parseCSV(inputStream);
            case "xlsx", "xls" -> parseExcel(inputStream);
            default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
        };
    }

    public List<String> parseHeaders(byte[] fileData, String fileType) throws Exception {
        InputStream inputStream = new ByteArrayInputStream(fileData);

        return switch (fileType.toLowerCase()) {
            case "csv" -> parseHeadersCSV(inputStream);
            case "xlsx", "xls" -> parseHeadersExcel(inputStream);
            default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
        };
    }

    public List<Map<String, String>> parseSampleRows(byte[] fileData, String fileType, int maxRows) throws Exception {
        InputStream inputStream = new ByteArrayInputStream(fileData);

        return switch (fileType.toLowerCase()) {
            case "csv" -> parseSampleRowsCSV(inputStream, maxRows);
            case "xlsx", "xls" -> parseSampleRowsExcel(inputStream, maxRows);
            default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
        };
    }

    public int countRows(byte[] fileData, String fileType) throws Exception {
        InputStream inputStream = new ByteArrayInputStream(fileData);

        return switch (fileType.toLowerCase()) {
            case "csv" -> countRowsCSV(inputStream);
            case "xlsx", "xls" -> countRowsExcel(inputStream);
            default -> throw new UnsupportedFileTypeException("Unsupported file type: " + fileType);
        };
    }

    private List<String> parseHeadersCSV(InputStream inputStream) throws Exception {
        try (CSVReader reader = new CSVReaderBuilder(new InputStreamReader(inputStream, StandardCharsets.UTF_8)).build()) {
            String[] headers = reader.readNext();
            return headers != null ? Arrays.asList(headers) : Collections.emptyList();
        }
    }

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

    private List<Map<String, String>> parseSampleRowsCSV(InputStream inputStream, int maxRows) throws Exception {
        try (CSVReader reader = new CSVReaderBuilder(new InputStreamReader(inputStream, StandardCharsets.UTF_8)).build()) {
            List<String[]> allRows = reader.readAll();
            if (allRows.isEmpty()) {
                return Collections.emptyList();
            }

            String[] headers = allRows.get(0);
            List<Map<String, String>> result = new ArrayList<>();

            int limit = Math.min(allRows.size() - 1, maxRows);
            for (int i = 1; i <= limit; i++) {
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

    private int countRowsCSV(InputStream inputStream) throws Exception {
        try (CSVReader reader = new CSVReaderBuilder(new InputStreamReader(inputStream, StandardCharsets.UTF_8)).build()) {
            List<String[]> allRows = reader.readAll();
            return Math.max(0, allRows.size() - 1); // Exclude header
        }
    }

    private int countRowsExcel(InputStream inputStream) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            return Math.max(0, lastRowNum); // Row 0 is header, so lastRowNum is already the count of data rows
        }
    }
}
