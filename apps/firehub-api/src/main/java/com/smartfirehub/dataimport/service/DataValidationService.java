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

  /**
   * UPSERT 모드에서 파일 내 중복 PK 행을 last-write-wins 규칙으로 접는다(dedup).
   *
   * <p>왜 필요한가: 동일 PK가 한 배치의 {@code INSERT ... ON CONFLICT DO UPDATE} 안에 두 번 이상 들어가면 Postgres가 "ON
   * CONFLICT DO UPDATE command cannot affect row a second time" 오류로 배치 전체를 거부한다. {@link
   * #validatePrimaryKeys}가 이미 "last-write-wins" 의도를 경고로 알리지만 실제 접기는 하지 않았기 때문에, 배치 적재 직전에 이 메서드로
   * 중복을 제거해야 한다.
   *
   * <p>composite key는 {@link #validatePrimaryKeys}와 동일하게 null-byte 구분자로 구성하며, 같은 키의 마지막 occurrence
   * 값이 남는다(원래 등장 순서는 유지).
   *
   * @param rows 컬럼명→값 형태의 행 목록 (적재 대상)
   * @param pkColumns 주 키 컬럼명 목록
   * @return 중복이 제거된 행 목록과 제거 건수
   */
  public DedupResult dedupeByPrimaryKeysLastWins(
      List<Map<String, Object>> rows, List<String> pkColumns) {
    // 등장 순서를 보존하면서 같은 PK는 마지막 값으로 덮어쓰기 위해 LinkedHashMap 사용
    java.util.LinkedHashMap<String, Map<String, Object>> byKey = new java.util.LinkedHashMap<>();

    for (Map<String, Object> row : rows) {
      // validatePrimaryKeys와 동일한 방식으로 복합 키 문자열 생성 (null → "")
      StringBuilder keyBuilder = new StringBuilder();
      for (int i = 0; i < pkColumns.size(); i++) {
        if (i > 0) keyBuilder.append('\0');
        Object value = row.get(pkColumns.get(i));
        keyBuilder.append(value != null ? value.toString() : "");
      }
      // put은 기존 위치를 유지한 채 값만 마지막 occurrence로 덮어쓴다 → last-write-wins
      byKey.put(keyBuilder.toString(), row);
    }

    int removedCount = rows.size() - byKey.size();
    return new DedupResult(new java.util.ArrayList<>(byKey.values()), removedCount);
  }

  public record PkValidationResult(List<ValidationErrorDetail> errors, List<String> warnings) {}

  /** dedupeByPrimaryKeysLastWins 결과: 중복 제거된 행 목록과 제거 건수. */
  public record DedupResult(List<Map<String, Object>> rows, int removedCount) {}

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
