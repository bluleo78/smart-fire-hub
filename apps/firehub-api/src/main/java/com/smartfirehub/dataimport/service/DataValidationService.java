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
          DateTimeFormatter.ofPattern("MM/dd/yyyy"),
          // 구분자 없는 8자리 yyyyMMdd(예: 20200316) — 공공/레거시 CSV에 흔한 형식
          DateTimeFormatter.ofPattern("yyyyMMdd"));

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
    // 단일 파일 전체를 한 번에 검증하는 기존 호출 경로 — 오프셋 없이(0) 위임
    return validate(rows, columns, 0);
  }

  /**
   * 대용량 파일을 배치 단위로 스트리밍 임포트할 때, 배치별로 잘라 검증하면서도 에러 메시지의 행 번호가 파일 전체 기준(전역)이 되도록 오프셋을 적용한다.
   *
   * @param rowIndexBase 이번 배치 이전까지 이미 처리된 행 수(전역 오프셋). 파일 처음부터 검증하면 0.
   */
  public ValidationResult validate(
      List<Map<String, String>> rows, List<DatasetColumnResponse> columns, int rowIndexBase) {
    List<List<Object>> validRows = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    int rowIndex = rowIndexBase;

    for (Map<String, String> row : rows) {
      rowIndex++;

      // 매핑 없는 경로도 매핑 경로(validateWithMapping/toRows)와 동일한 셀 변환 로직(convertRowOrNull)을
      // 공유한다 — 별도 인라인 변환 루프를 유지하면 두 경로가 갈라져 "검증 통과 == 값 변환 성공"이 어긋나는
      // 미묘한 버그(Task2 핵심 위험)가 생긴다. 에러 형식은 기존 String 메시지 포맷을 그대로 유지한다.
      List<ValidationErrorDetail> rowErrors = new ArrayList<>();
      List<Object> convertedRow = convertRowOrNull(row, columns, rowIndex, rowErrors);

      if (convertedRow != null) {
        validRows.add(convertedRow);
      } else {
        for (ValidationErrorDetail detail : rowErrors) {
          // "Required field is empty"는 convertRowOrNull의 필수값 누락 메시지 — 기존 문구로 복원
          String suffix =
              "Required field is empty".equals(detail.error())
                  ? "is required but empty"
                  : "- " + detail.error();
          errors.add(
              "Row " + detail.rowNumber() + ": column '" + detail.columnName() + "' " + suffix);
        }
      }
    }

    return new ValidationResult(validRows, errors, rows.size(), validRows.size(), errors.size());
  }

  /** 컬럼 데이터타입이 DATE 또는 TIMESTAMP인지 판정한다. 무의미한 날짜없음 표식 정규화는 이 두 타입에 한정한다. */
  private static boolean isDateOrTimestampType(String dataType) {
    return "DATE".equals(dataType) || "TIMESTAMP".equals(dataType);
  }

  /**
   * 값이 "무의미한 날짜없음 표식"인지 판정한다. 레거시/공공 데이터에서 날짜 없음을 "0", "0000-00-00", "00000000",
   * "0000/00/00", "00000000000000" 등으로 표기하는 경우가 있어, 어떤 포맷으로도 파싱되지 않아 "Invalid date value: 0"으로
   * 거부되던 문제를 방지하기 위함이다.
   *
   * <p>과검출 방지: 구분자(-, /, 공백, :)를 제거한 뒤 남은 문자열이 최소 1자 이상이면서 전부 '0'인 경우에만 무의미값으로 판정한다. 유효한 날짜(예:
   * "2020-03-16" → 구분자 제거 시 "20200316")는 0이 아닌 문자가 섞여 있으므로 오검출되지 않는다.
   */
  private static boolean isMeaninglessDateValue(String value) {
    if (value == null) return false;
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return false; // 빈 값은 기존 empty 처리 경로에서 이미 다룸

    String stripped = trimmed.replaceAll("[-/ :]", "");
    if (stripped.isEmpty()) return false;

    for (int i = 0; i < stripped.length(); i++) {
      if (stripped.charAt(i) != '0') return false;
    }
    return true;
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
          // 천단위 콤마(예: "38,344") 제거 후 파싱. 임포트 데이터에 흔한 형식.
          yield Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
          throw new Exception("Invalid integer value: " + value);
        }
      }
      case "DECIMAL" -> {
        try {
          // 천단위 콤마 제거 후 파싱(소수 구분자는 마침표 전제). BigDecimal 은 콤마를 못 받음.
          yield new BigDecimal(value.replace(",", ""));
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
                  + " MM/dd/yyyy, yyyyMMdd)");
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
    // 단일 파일 전체를 한 번에 검증하는 기존 호출 경로 — 오프셋 없이(0) 위임
    return validateWithMapping(rows, columns, mappings, 0);
  }

  /**
   * 대용량 파일을 배치 단위로 스트리밍 임포트할 때, 배치별로 잘라 검증하면서도 {@link ValidationErrorDetail#rowNumber()}가 파일 전체
   * 기준(전역)이 되도록 오프셋을 적용한다.
   *
   * @param rowIndexBase 이번 배치 이전까지 이미 처리된 행 수(전역 오프셋). 파일 처음부터 검증하면 0.
   */
  public ValidationResultWithDetails validateWithMapping(
      List<Map<String, String>> rows,
      List<DatasetColumnResponse> columns,
      List<ColumnMappingEntry> mappings,
      int rowIndexBase) {

    // Build mapping lookup: fileColumn -> datasetColumn
    Map<String, String> columnMapping = buildColumnMapping(mappings);

    List<List<Object>> validRows = new ArrayList<>();
    List<ValidationErrorDetail> errors = new ArrayList<>();
    int rowIndex = rowIndexBase;

    for (Map<String, String> row : rows) {
      rowIndex++;

      // Remap row using column mappings
      Map<String, String> remappedRow = remapRow(row, columnMapping);

      // Pass1(검증)과 Pass2(삽입의 toRows)가 동일한 변환 로직(convertRowOrNull)을 호출한다 —
      // 두 벌로 갈라지면 "검증 통과 == 값 변환 성공"이 어긋나는 미묘한 버그가 생긴다(Task2 핵심 위험).
      List<Object> convertedRow = convertRowOrNull(remappedRow, columns, rowIndex, errors);
      if (convertedRow != null) {
        validRows.add(convertedRow);
      }
    }

    return new ValidationResultWithDetails(
        validRows, errors, rows.size(), validRows.size(), errors.size());
  }

  /**
   * 배치의 각 행을 데이터셋 컬럼 순서에 맞는 값 리스트로 변환한다(임포트 잡 Pass2 삽입 전용).
   *
   * <p>Pass1 검증({@link #validateWithMapping})과 반드시 같은 셀 변환 로직({@link #convertRowOrNull})을 거치므로,
   * "검증 통과 == 값 변환 성공"이 두 경로에서 어긋나지 않는다. 매핑 없는 경로({@code mappings=null} 또는 빈 리스트)는 {@link
   * #validate}와 동일하게 컬럼명으로 행을 직접 조회한다. Pass2는 전량 검증을 통과한 배치에서만 호출되므로 변환 실패(null 반환)는
   * 발생하지 않는 것이 전제다 — 이 전제가 깨지면(변환기 불변식 위반) 빈 행을 조용히 삽입하는 대신 즉시 예외로 실패시킨다.
   */
  public List<List<Object>> toRows(
      List<Map<String, String>> rows,
      List<DatasetColumnResponse> columns,
      List<ColumnMappingEntry> mappings) {
    boolean hasMappings = mappings != null && !mappings.isEmpty();
    Map<String, String> columnMapping = hasMappings ? buildColumnMapping(mappings) : Map.of();
    List<ValidationErrorDetail> discardedErrors = new ArrayList<>();

    List<List<Object>> result = new ArrayList<>();
    int rowIndex = 0;
    for (Map<String, String> row : rows) {
      rowIndex++;
      Map<String, String> effectiveRow = hasMappings ? remapRow(row, columnMapping) : row;
      List<Object> convertedRow = convertRowOrNull(effectiveRow, columns, rowIndex, discardedErrors);
      if (convertedRow == null) {
        // Pass1(검증)을 통과한 행이 Pass2(삽입)에서 변환 실패하는 것은 불변식 위반이다.
        // 조용히 빈 행을 삽입하면 데이터 정합성이 깨지므로 즉시 실패시킨다.
        throw new IllegalStateException(
            "검증을 통과한 행이 삽입 변환에 실패했습니다 (row " + rowIndex + ") — 변환기 불변식 위반");
      }
      result.add(convertedRow);
    }
    return result;
  }

  /** {@link ColumnMappingEntry} 목록을 fileColumn -> datasetColumn lookup map으로 변환한다. */
  private Map<String, String> buildColumnMapping(List<ColumnMappingEntry> mappings) {
    Map<String, String> columnMapping = new HashMap<>();
    if (mappings == null) {
      return columnMapping;
    }
    for (ColumnMappingEntry mapping : mappings) {
      if (mapping.datasetColumn() != null) {
        columnMapping.put(mapping.fileColumn(), mapping.datasetColumn());
      }
    }
    return columnMapping;
  }

  /** fileColumn 키의 행을 datasetColumn 키로 리매핑한다. */
  private Map<String, String> remapRow(Map<String, String> row, Map<String, String> columnMapping) {
    Map<String, String> remappedRow = new HashMap<>();
    for (Map.Entry<String, String> entry : row.entrySet()) {
      String datasetColumn = columnMapping.get(entry.getKey());
      if (datasetColumn != null) {
        remappedRow.put(datasetColumn, entry.getValue());
      }
    }
    return remappedRow;
  }

  /**
   * 한 행(매핑 경로는 이미 매핑 적용됨)을 데이터셋 컬럼 순서의 값 리스트로 변환한다. 필수값 누락/타입 변환 실패는 errorSink에 기록하고
   * null을 반환한다(행 전체 무효). {@link #validate}, {@link #validateWithMapping}, {@link #toRows} 세 경로 모두
   * 이 메서드를 공유해 변환 로직이 하나로 유지되게 한다(Task2 핵심 위험 3) — 두 벌로 갈라지면 "검증 통과 == 값 변환 성공"이 어긋나는
   * 미묘한 버그가 생긴다.
   */
  private List<Object> convertRowOrNull(
      Map<String, String> row,
      List<DatasetColumnResponse> columns,
      int rowIndex,
      List<ValidationErrorDetail> errorSink) {
    List<Object> convertedRow = new ArrayList<>();
    boolean rowValid = true;

    for (DatasetColumnResponse column : columns) {
      String rawValue = row.get(column.columnName());

      // DATE/TIMESTAMP 컬럼의 "0", "0000-00-00" 등 무의미한 날짜없음 표식은 빈 값과 동일하게 취급하여
      // 아래의 기존 빈 값 처리 경로(필수면 에러, nullable이면 null 저장)를 타게 한다.
      // "Invalid date value: 0" 형태로 거부하지 않기 위함.
      if (isDateOrTimestampType(column.dataType()) && isMeaninglessDateValue(rawValue)) {
        rawValue = null;
      }

      // Check required field
      if (!column.isNullable() && (rawValue == null || rawValue.trim().isEmpty())) {
        errorSink.add(
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
        errorSink.add(
            new ValidationErrorDetail(rowIndex, column.columnName(), rawValue, e.getMessage()));
        rowValid = false;
      }
    }

    return rowValid ? convertedRow : null;
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
