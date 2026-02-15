package com.smartfirehub.dataimport.service;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class DataValidationService {

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy")
    );

    private static final List<DateTimeFormatter> TIMESTAMP_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    );

    public ValidationResult validate(List<Map<String, String>> rows, List<DatasetColumnResponse> columns) {
        List<List<Object>> validRows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int rowIndex = 0;

        for (Map<String, String> row : rows) {
            rowIndex++;
            List<Object> convertedRow = new ArrayList<>();
            boolean rowValid = true;

            for (DatasetColumnResponse column : columns) {
                String rawValue = row.get(column.columnName());

                // Check required field
                if (!column.isNullable() && (rawValue == null || rawValue.trim().isEmpty())) {
                    errors.add("Row " + rowIndex + ": column '" + column.columnName() + "' is required but empty");
                    rowValid = false;
                    continue;
                }

                // Handle null/empty values
                if (rawValue == null || rawValue.trim().isEmpty()) {
                    convertedRow.add(null);
                    continue;
                }

                // Convert and validate based on data type
                try {
                    Object convertedValue = convertValue(rawValue.trim(), column.dataType());
                    convertedRow.add(convertedValue);
                } catch (Exception e) {
                    errors.add("Row " + rowIndex + ": column '" + column.columnName() + "' - " + e.getMessage());
                    rowValid = false;
                }
            }

            if (rowValid) {
                validRows.add(convertedRow);
            }
        }

        return new ValidationResult(validRows, errors, rows.size(), validRows.size(), errors.size());
    }

    public Object convertValue(String value, String dataType) throws Exception {
        if (value == null || value.isEmpty()) {
            return null;
        }

        return switch (dataType) {
            case "TEXT" -> value;
            case "INTEGER" -> {
                try {
                    yield Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new Exception("Invalid integer value: " + value);
                }
            }
            case "DECIMAL" -> {
                try {
                    yield new BigDecimal(value);
                } catch (NumberFormatException e) {
                    throw new Exception("Invalid decimal value: " + value);
                }
            }
            case "BOOLEAN" -> {
                String lower = value.toLowerCase();
                if (lower.equals("true") || lower.equals("1") || lower.equals("yes")) {
                    yield true;
                } else if (lower.equals("false") || lower.equals("0") || lower.equals("no")) {
                    yield false;
                } else {
                    throw new Exception("Invalid boolean value: " + value + " (expected: true/false/1/0/yes/no)");
                }
            }
            case "DATE" -> {
                LocalDate date = null;
                for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                    try {
                        date = LocalDate.parse(value, formatter);
                        break;
                    } catch (DateTimeParseException ignored) {
                    }
                }
                if (date == null) {
                    throw new Exception("Invalid date value: " + value + " (expected formats: yyyy-MM-dd, yyyy/MM/dd, dd-MM-yyyy, dd/MM/yyyy, MM/dd/yyyy)");
                }
                yield date;
            }
            case "TIMESTAMP" -> {
                LocalDateTime timestamp = null;
                for (DateTimeFormatter formatter : TIMESTAMP_FORMATTERS) {
                    try {
                        timestamp = LocalDateTime.parse(value, formatter);
                        break;
                    } catch (DateTimeParseException ignored) {
                    }
                }
                if (timestamp == null) {
                    throw new Exception("Invalid timestamp value: " + value + " (expected formats: yyyy-MM-dd HH:mm:ss, ISO format)");
                }
                yield timestamp;
            }
            default -> throw new Exception("Unknown data type: " + dataType);
        };
    }

    public record ValidationResult(
            List<List<Object>> validRows,
            List<String> errors,
            int totalRows,
            int validCount,
            int errorCount
    ) {}
}
