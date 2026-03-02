package com.smartfirehub.dataimport.service;

import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ValidationErrorDetail;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.HashSet;
import org.springframework.stereotype.Service;

@Service
public class DataValidationService {

  private static final List<DateTimeFormatter> DATE_FORMATTERS =
      List.of(
          DateTimeFormatter.ofPattern("yyyy-MM-dd"),
          DateTimeFormatter.ofPattern("yyyy/MM/dd"),
          DateTimeFormatter.ofPattern("dd-MM-yyyy"),
          DateTimeFormatter.ofPattern("dd/MM/yyyy"),
          DateTimeFormatter.ofPattern("MM/dd/yyyy"));

  private static final List<DateTimeFormatter> TIMESTAMP_FORMATTERS =
      List.of(
          DateTimeFormatter.ISO_LOCAL_DATE_TIME,
          DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
          DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
          DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
          DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
          DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

  public ValidationResult validate(
      List<Map<String, String>> rows, List<DatasetColumnResponse> columns) {
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
          errors.add(
              "Row " + rowIndex + ": column '" + column.columnName() + "' is required but empty");
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
          errors.add(
              "Row " + rowIndex + ": column '" + column.columnName() + "' - " + e.getMessage());
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

    // Strip surrounding quotes from CSV parsing
    if (value.length() >= 2
        && ((value.startsWith("'") && value.endsWith("'"))
            || (value.startsWith("\"") && value.endsWith("\"")))) {
      value = value.substring(1, value.length() - 1);
    } else if (value.startsWith("'") || value.startsWith("\"")) {
      value = value.substring(1);
    }

    return switch (dataType) {
      case "TEXT", "VARCHAR" -> value;
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
          throw new Exception(
              "Invalid boolean value: " + value + " (expected: true/false/1/0/yes/no)");
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
          throw new Exception(
              "Invalid date value: "
                  + value
                  + " (expected formats: yyyy-MM-dd, yyyy/MM/dd, dd-MM-yyyy, dd/MM/yyyy,"
                  + " MM/dd/yyyy)");
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
          throw new Exception(
              "Invalid timestamp value: "
                  + value
                  + " (expected formats: yyyy-MM-dd HH:mm:ss, yyyyMMddHHmmss, ISO format)");
        }
        yield timestamp;
      }
      case "GEOMETRY" -> {
        // GeoJSON string — pass through as-is (PostGIS will validate on INSERT)
        yield value;
      }
      default -> throw new Exception("Unknown data type: " + dataType);
    };
  }

  public ValidationResultWithDetails validateWithMapping(
      List<Map<String, String>> rows,
      List<DatasetColumnResponse> columns,
      List<ColumnMappingEntry> mappings) {

    // Build mapping lookup: fileColumn -> datasetColumn
    Map<String, String> columnMapping = new HashMap<>();
    for (ColumnMappingEntry mapping : mappings) {
      if (mapping.datasetColumn() != null) {
        columnMapping.put(mapping.fileColumn(), mapping.datasetColumn());
      }
    }

    // Build column lookup by name
    Map<String, DatasetColumnResponse> columnsByName = new HashMap<>();
    for (DatasetColumnResponse col : columns) {
      columnsByName.put(col.columnName(), col);
    }

    List<List<Object>> validRows = new ArrayList<>();
    List<ValidationErrorDetail> errors = new ArrayList<>();
    int rowIndex = 0;

    for (Map<String, String> row : rows) {
      rowIndex++;

      // Remap row using column mappings
      Map<String, String> remappedRow = new HashMap<>();
      for (Map.Entry<String, String> entry : row.entrySet()) {
        String fileColumn = entry.getKey();
        String datasetColumn = columnMapping.get(fileColumn);
        if (datasetColumn != null) {
          remappedRow.put(datasetColumn, entry.getValue());
        }
      }

      List<Object> convertedRow = new ArrayList<>();
      boolean rowValid = true;

      for (DatasetColumnResponse column : columns) {
        String rawValue = remappedRow.get(column.columnName());

        // Check required field
        if (!column.isNullable() && (rawValue == null || rawValue.trim().isEmpty())) {
          errors.add(
              new ValidationErrorDetail(
                  rowIndex,
                  column.columnName(),
                  rawValue != null ? rawValue : "",
                  "Required field is empty"));
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
          errors.add(
              new ValidationErrorDetail(rowIndex, column.columnName(), rawValue, e.getMessage()));
          rowValid = false;
        }
      }

      if (rowValid) {
        validRows.add(convertedRow);
      }
    }

    return new ValidationResultWithDetails(
        validRows, errors, rows.size(), validRows.size(), errors.size());
  }

  public PkValidationResult validatePrimaryKeys(
      List<Map<String, String>> rows, List<String> pkColumns) {
    List<String> warnings = new ArrayList<>();
    List<ValidationErrorDetail> errors = new ArrayList<>();

    // Track composite keys seen so far to detect duplicates within the file
    Set<String> seenKeys = new HashSet<>();

    int rowIndex = 0;
    for (Map<String, String> row : rows) {
      rowIndex++;

      // Check for NULL or empty PK values
      for (String pkCol : pkColumns) {
        String value = row.get(pkCol);
        if (value == null || value.trim().isEmpty()) {
          errors.add(
              new ValidationErrorDetail(
                  rowIndex,
                  pkCol,
                  value != null ? value : "",
                  "Primary key column cannot be null or empty"));
        }
      }

      // Build composite key string using null-byte as separator (safe since values are trimmed
      // strings)
      StringBuilder keyBuilder = new StringBuilder();
      for (int i = 0; i < pkColumns.size(); i++) {
        if (i > 0) keyBuilder.append("\0");
        String value = row.get(pkColumns.get(i));
        keyBuilder.append(value != null ? value : "");
      }
      String compositeKey = keyBuilder.toString();

      // Detect duplicate keys within the file — last-write-wins in UPSERT mode
      if (!seenKeys.add(compositeKey)) {
        warnings.add(
            "Row "
                + rowIndex
                + ": duplicate key value ("
                + String.join(", ", pkColumns)
                + ") = ("
                + compositeKey.replace("\0", ", ")
                + ") — last-write-wins in UPSERT mode");
      }
    }

    return new PkValidationResult(errors, warnings);
  }

  public record PkValidationResult(List<ValidationErrorDetail> errors, List<String> warnings) {}

  public record ValidationResult(
      List<List<Object>> validRows,
      List<String> errors,
      int totalRows,
      int validCount,
      int errorCount) {}

  public record ValidationResultWithDetails(
      List<List<Object>> validRows,
      List<ValidationErrorDetail> errors,
      int totalRows,
      int validCount,
      int errorCount) {}
}
