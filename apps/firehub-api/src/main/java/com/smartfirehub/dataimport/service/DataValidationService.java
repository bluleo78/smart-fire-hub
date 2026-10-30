package com.smartfirehub.dataimport.service;

import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ValidationErrorDetail;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.service.ColumnPhysicalType;
import com.smartfirehub.dataset.service.GeoJsonValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class DataValidationService {

  private static final List<DateTimeFormatter> DATE_FORMATTERS =
      List.of(
          DateTimeFormatter.ofPattern("yyyy-MM-dd"),
          DateTimeFormatter.ofPattern("yyyy/MM/dd"),
          // 일·월이 앞 두 칸인 형식(dd/MM/yyyy·MM/dd/yyyy 등)은 여기서 다루지 않는다 — 형식 목록을 순서대로 시도해 첫 성공을 쓰면
          // 01/02/2026 은 2월 1일, 12/31/2026 은 12월 31일로 같은 열 안에서 행마다 해석이 갈렸다(WD-246). 열 단위로 순서를
          // 정하는 parseDayMonthLeading 이 맡는다.
          // 구분자 없는 8자리 yyyyMMdd(예: 20200316) — 공공/레거시 CSV에 흔한 형식
          DateTimeFormatter.ofPattern("yyyyMMdd"));

  private static final List<DateTimeFormatter> TIMESTAMP_FORMATTERS =
      List.of(
          DateTimeFormatter.ISO_LOCAL_DATE_TIME,
          // 소수 초(.123·.123456)는 있어도 없어도 된다 — 내보낸 CSV(WD-159)·PG 텍스트 원문 모양을 그대로 다시 임포트할 수 있게
          new DateTimeFormatterBuilder()
              .appendPattern("yyyy-MM-dd HH:mm:ss")
              .optionalStart()
              .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
              .optionalEnd()
              .toFormatter(),
          DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
          // dd-MM-yyyy HH:mm:ss·dd/MM/yyyy HH:mm:ss 는 parseDayMonthLeading 이 열 단위 순서로 해석한다(WD-246).
          DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

  // 필수값 누락 사유 문구. validate()가 요약 문자열을 조립할 때 이 값과 동일한지 비교해 문구를 분기하므로,
  // 리터럴을 여러 곳에 흩뿌리지 않고 상수 하나로 묶어 두 곳이 어긋나지 않게 한다.
  static final String REQUIRED_FIELD_EMPTY = "필수 값이 비어 있습니다";

  /** DATE 열이 자정이면 날짜로 받는 날짜시각 형식(ISO 와 엑셀 파서 출력 모양). */
  private static final List<DateTimeFormatter> MIDNIGHT_DATE_TIME_FORMATTERS =
      List.of(
          DateTimeFormatter.ISO_LOCAL_DATE_TIME,
          DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

  /**
   * 날짜 없이 시각만 있는 값(예: 12:00:00). 엑셀 h:mm 서식 셀(일련값 &lt; 1)을 파서가 이렇게 읽는다 — 예전엔 1899-12-31 가짜 날짜가 붙어
   * TIMESTAMP 열에 그대로 저장됐다(WD-252).
   */
  private static final Pattern TIME_ONLY = Pattern.compile("\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");

  static final String TIME_ONLY_MESSAGE = "시각만 있는 값은 날짜로 저장할 수 없습니다(날짜를 함께 넣거나 텍스트 열로 가져와 주세요): ";

  /** 콤마 제거 후 부호와 숫자로만 이뤄진 정수 리터럴 — 이 형식이 아니면 형식 오류, 맞으면 BIGINT 범위만 남는다. */
  private static final Pattern INTEGER_LITERAL = Pattern.compile("[+-]?\\d+");

  /**
   * 쉼표가 든 숫자 값의 정수부가 올바른 천 단위 묶음인지(WD-370) — 앞 1~3자리 뒤로 「,세 자리」가 이어져야 한다(예: {@code 1,000}·{@code
   * 38,344}·{@code -1,234}). 소수부에는 쉼표가 올 수 없다.
   */
  private static final Pattern THOUSANDS_GROUPED =
      Pattern.compile("[+-]?\\d{1,3}(,\\d{3})+(\\.\\d*)?");

  /** 쉼표 위치가 천 단위가 아닌 숫자 값의 안내 — 어떻게 고치면 되는지 함께 알린다(WD-370). */
  static final String COMMA_FORMAT_HINT = " (소수점은 마침표, 천 단위 쉼표는 3자리마다)";

  /**
   * 일·월이 앞 두 칸이고 연도가 뒤에 오는 날짜(예: 31/12/2026, 12-31-2026, 1.2.2026)와 선택적 시각부(HH:mm:ss). 두 구분자는 같아야
   * 한다. 앞 두 칸 중 어느 쪽이 일인지는 값 하나로 알 수 없으므로 {@link DateOrderContext} 가 열 단위로 정한다(WD-246).
   */
  private static final Pattern DAY_MONTH_LEADING =
      Pattern.compile("(\\d{1,2})([/.-])(\\d{1,2})\\2(\\d{4})(?: (\\d{2}):(\\d{2}):(\\d{2}))?");

  /** 일·월이 앞에 오는 날짜의 두 칸 순서. */
  enum DayMonthOrder {
    /** 일/월/연도(예: 31/12/2026) — 판단 근거가 없을 때의 기본값(기존 동작). */
    DAY_FIRST("일/월/연도"),
    /** 월/일/연도(예: 12/31/2026). */
    MONTH_FIRST("월/일/연도");

    private final String label;

    DayMonthOrder(String label) {
      this.label = label;
    }
  }

  /**
   * 한 번의 임포트(사전검증 샘플 또는 임포트 잡 전체)에서 열마다 일·월 순서를 하나로 정해 두는 상태(WD-246).
   *
   * <p>왜 필요한가: 01/02/2026 처럼 앞 두 칸이 모두 12 이하인 값은 값 하나로 일/월인지 월/일인지 알 수 없다. 예전에는 값마다 형식 목록의 첫 성공을 써서
   * 같은 열 안에서도 12/31/2026 은 월/일, 01/02/2026 은 일/월로 섞여 저장됐다. 이제 열의 값 중 첫 칸이 12 를 넘는 값이 있으면 일/월/연도, 둘째
   * 칸이 12 를 넘는 값이 있으면 월/일/연도로 열 전체를 해석하고, 둘 다 나오면 섞인 열로 거부한다. 판단 근거가 하나도 없으면(모두 12 이하) 기존 기본값인
   * 일/월/연도를 쓴다.
   *
   * <p>임포트 잡은 검증(Pass1)이 파일 전체를 먼저 훑은 뒤 삽입(Pass2)을 다시 훑으므로, 같은 인스턴스를 두 패스에 넘기면 Pass1 이 정한 순서로 Pass2
   * 가 모든 행(근거 값보다 앞에 있던 모호한 행 포함)을 해석한다 — 파일을 더 읽지 않는다. 열 이름을 키로 쓰고, 한 임포트 안에서만 쓰며 스레드 간에 공유하지 않는다.
   */
  public static final class DateOrderContext {

    /** 열 순서를 처음 정한 값과 그 행 번호 — 섞인 열 오류 문구에 함께 보여 준다. */
    private record Decision(DayMonthOrder order, String value, int rowNumber) {}

    private final Map<String, Decision> decisions = new HashMap<>();

    /** 열의 일·월 순서. 아직 근거가 없으면 기본값(일/월/연도). */
    DayMonthOrder orderOf(String columnName) {
      Decision decision = decisions.get(columnName);
      return decision != null ? decision.order() : DayMonthOrder.DAY_FIRST;
    }

    /** 이미 다른 순서로 정해진 열이면 섞인 열 오류를 던진다. */
    void checkConsistent(String columnName, DayMonthOrder evidence, String value) throws Exception {
      Decision decision = decisions.get(columnName);
      if (decision != null && decision.order() != evidence) {
        throw new Exception(
            "같은 열에 일/월/연도와 월/일/연도 형식이 섞여 있습니다: "
                + decision.value()
                + (decision.rowNumber() > 0 ? "(" + decision.rowNumber() + "행, " : "(")
                + decision.order().label
                + "), "
                + value
                + "("
                + evidence.label
                + ") — 한 열의 날짜는 한 가지 순서로 맞춰 주세요");
      }
    }

    /** 첫 근거 값으로 열 순서를 정한다(이미 같은 순서로 정해져 있으면 그대로 둔다). */
    void record(String columnName, DayMonthOrder evidence, String value, int rowNumber) {
      decisions.putIfAbsent(columnName, new Decision(evidence, value, rowNumber));
    }
  }

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
    // 이 호출 하나(샘플·단일 배치)만 보고 열의 일·월 순서를 정한다 — 여러 배치에 걸친 임포트 잡은 아래 오버로드로 상태를 넘긴다.
    return validate(rows, columns, rowIndexBase, new DateOrderContext());
  }

  /**
   * {@link #validate(List, List, int)} 와 같되, 열의 일·월 순서 판정 상태를 호출자가 넘긴다 — 임포트 잡이 배치마다 같은 상태를 넘겨 파일
   * 전체에서 열 순서를 하나로 정하고, 삽입({@link #toRows(List, List, List, DateOrderContext)})에도 같은 상태를
   * 쓴다(WD-246).
   */
  public ValidationResult validate(
      List<Map<String, String>> rows,
      List<DatasetColumnResponse> columns,
      int rowIndexBase,
      DateOrderContext dateOrders) {
    List<List<Object>> validRows = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    int rowIndex = rowIndexBase;

    for (Map<String, String> row : rows) {
      rowIndex++;

      // 매핑 없는 경로도 매핑 경로(validateWithMapping/toRows)와 동일한 셀 변환 로직(convertRowOrNull)을
      // 공유한다 — 별도 인라인 변환 루프를 유지하면 두 경로가 갈라져 "검증 통과 == 값 변환 성공"이 어긋나는
      // 미묘한 버그(Task2 핵심 위험)가 생긴다. 에러는 기존과 동일한 "행/컬럼/사유" 구조의 String 메시지로 조립한다.
      List<ValidationErrorDetail> rowErrors = new ArrayList<>();
      List<Object> convertedRow = convertRowOrNull(row, columns, rowIndex, rowErrors, dateOrders);

      if (convertedRow != null) {
        validRows.add(convertedRow);
      } else {
        for (ValidationErrorDetail detail : rowErrors) {
          // 한국어 UI와 문구를 맞추기 위해 요약 문자열도 한국어로 조립한다(#309).
          // 필수값 누락은 값 자체가 없으므로 "값: ..."을 붙이지 않고 사유만 표기한다.
          String suffix =
              REQUIRED_FIELD_EMPTY.equals(detail.error())
                  ? REQUIRED_FIELD_EMPTY
                  : "- " + detail.error();
          errors.add(detail.rowNumber() + "행 '" + detail.columnName() + "' 컬럼 " + suffix);
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
   * 값이 "무의미한 날짜없음 표식"인지 판정한다. 레거시/공공 데이터에서 날짜 없음을 "0", "0000-00-00", "00000000", "0000/00/00",
   * "00000000000000" 등으로 표기하는 경우가 있어, 어떤 포맷으로도 파싱되지 않아 "날짜 형식이 아닙니다: 0"으로 거부되던 문제를 방지하기 위함이다.
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
    // 값 하나만 변환하는 경로 — 열 순서 근거는 이 값 자체뿐이다(모호하면 일/월/연도).
    return convertValue(value, dataType, "", new DateOrderContext(), 0);
  }

  /**
   * 숫자 값에서 천 단위 쉼표만 지운다(WD-370). 쉼표가 없으면 그대로 돌려준다.
   *
   * <p>예전에는 쉼표를 자리와 상관없이 모두 지워 「12,5」(소수 쉼표)가 125, 「1,2,3」이 123 으로 「검증 성공」 뒤 저장됐다. 정수부가 올바른 3자리
   * 묶음({@link #THOUSANDS_GROUPED})일 때만 지우고, 그 밖의 쉼표는 형식 오류로 알려 사용자가 원본을 고치게 한다.
   *
   * @param errorPrefix 형식 오류 문구 앞부분(정수·소수 칸별 기존 문구) — 뒤에 원래 값과 고치는 법을 붙인다
   */
  private static String stripThousandsSeparators(String value, String errorPrefix)
      throws Exception {
    if (value.indexOf(',') < 0) {
      return value;
    }
    if (!THOUSANDS_GROUPED.matcher(value).matches()) {
      throw new Exception(errorPrefix + value + COMMA_FORMAT_HINT);
    }
    return value.replace(",", "");
  }

  /**
   * 값 하나를 컬럼 타입으로 변환한다. 일·월이 앞에 오는 날짜는 {@code dateOrders} 의 열 순서로 해석하고, 이 값이 순서의 근거가 되면 기록한다.
   *
   * @param columnName 열 순서 판정 키(데이터셋 컬럼명)
   * @param rowIndex 섞인 열 오류 문구에 쓰는 전역 행 번호(모르면 0)
   */
  private Object convertValue(
      String value, String dataType, String columnName, DateOrderContext dateOrders, int rowIndex)
      throws Exception {
    if (value == null || value.isEmpty()) {
      return null;
    }

    // 엑셀 오류 셀(#DIV/0! 등)은 어떤 타입으로도 받지 않는다 — 예전엔 TEXT 열에 "ERROR:#DIV/0!" 글자로 저장됐다(WD-252).
    // 표식은 엑셀 파서만 만들고 NUL 을 품고 있어 CSV 값과 겹치지 않는다.
    String excelError = LegacyExcelDataFormatter.errorCodeOf(value);
    if (excelError != null) {
      throw new Exception(
          "엑셀 오류 값(" + excelError + ")은 가져올 수 없습니다 — 원본 파일에서 수식이나 값을 고친 뒤 다시 가져와 주세요");
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
        // 천단위 콤마(예: "38,344")만 지우고 파싱한다 — 자리가 맞지 않는 쉼표("1,2,3")는 형식 오류(WD-370).
        String digits = stripThousandsSeparators(value, "정수 형식이 아닙니다: ");
        if (!INTEGER_LITERAL.matcher(digits).matches()) {
          throw new Exception("정수 형식이 아닙니다: " + value);
        }
        // 부호와 숫자만으로 된 값 — 형식이 아니라 BIGINT 범위만 남는다(초과 시 범위 사유로 구분, 행 API 와 같은 판정·문구).
        yield ColumnPhysicalType.toExactBigint(new BigDecimal(digits), value);
      }
      case "DECIMAL" -> {
        try {
          // 천단위 콤마만 지우고 파싱한다(소수 구분자는 마침표 전제). BigDecimal 은 콤마를 못 받음.
          // 소수 쉼표("12,5")를 지우면 125 로 10배가 되므로 자리가 맞지 않는 쉼표는 형식 오류로 막는다(WD-370).
          yield new BigDecimal(stripThousandsSeparators(value, "소수 형식이 아닙니다: "));
        } catch (NumberFormatException e) {
          throw new Exception("소수 형식이 아닙니다: " + value);
        }
      }
      case "BOOLEAN" -> {
        String lower = value.toLowerCase();
        if (lower.equals("true") || lower.equals("1") || lower.equals("yes")) {
          yield true;
        } else if (lower.equals("false") || lower.equals("0") || lower.equals("no")) {
          yield false;
        } else {
          throw new Exception("참/거짓 형식이 아닙니다: " + value + " (허용 값: true/false/1/0/yes/no)");
        }
      }
      case "DATE" -> {
        // 일·월이 앞에 오는 형식은 열 단위 순서로 해석한다(WD-246). 시각부가 붙은 값은 DATE 로 받지 않는다(아래에서 형식 오류).
        LocalDateTime dayMonth =
            parseDayMonthLeading(value, false, columnName, dateOrders, rowIndex);
        LocalDate date = dayMonth != null ? dayMonth.toLocalDate() : null;
        for (DateTimeFormatter formatter :
            date != null ? List.<DateTimeFormatter>of() : DATE_FORMATTERS) {
          try {
            date = LocalDate.parse(value, formatter);
            break;
          } catch (DateTimeParseException ignored) {
          }
        }
        // 자정 날짜시각은 날짜만 있는 값으로 받는다 — ISO 형식("2026-05-10T00:00", CSV·예전 파서 출력)과, 엑셀 파서
        // (LegacyExcelDataFormatter)가 날짜시각 서식 셀을 읽은 "2026-05-10 00:00:00" 모두. 내보낸 Excel(WD-159)을 고쳐
        // 다시
        // 임포트할 때와 사용자가 만든 날짜 셀 파일 모두에 해당한다. 자정이 아닌 시각은 날짜로 자르면 값이 바뀌므로 그대로 거부한다.
        for (DateTimeFormatter formatter :
            date != null ? List.<DateTimeFormatter>of() : MIDNIGHT_DATE_TIME_FORMATTERS) {
          try {
            LocalDateTime dateTime = LocalDateTime.parse(value, formatter);
            if (!dateTime.toLocalTime().equals(LocalTime.MIDNIGHT)) {
              // 엑셀 날짜 서식 셀은 날짜만 보여도 시각 소수가 숨어 있을 수 있다 — 왜 거부되는지 알 수 있게 시각을 짚어 준다.
              throw new Exception(
                  "날짜에 시각("
                      + dateTime.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                      + ")이 들어 있어 날짜 열에 저장하면 값이 바뀝니다: "
                      + value
                      + " (엑셀 셀이 날짜만 보여도 시각이 숨어 있을 수 있습니다 — 시각을 지우거나 날짜시간 열로 가져와 주세요)");
            }
            date = dateTime.toLocalDate();
            break;
          } catch (DateTimeParseException ignored) {
          }
        }
        if (date == null && TIME_ONLY.matcher(value).matches()) {
          throw new Exception(TIME_ONLY_MESSAGE + value);
        }
        if (date == null) {
          throw new Exception(
              "날짜 형식이 아닙니다: "
                  + value
                  + " (허용 형식: yyyy-MM-dd, yyyy/MM/dd, yyyyMMdd, dd/MM/yyyy, MM/dd/yyyy"
                  + " — 일·월·연도 구분자는 / - . 중 하나, 한 열에서는 한 가지 순서만)");
        }
        yield date;
      }
      case "TIMESTAMP" -> {
        // 일·월이 앞에 오는 날짜(시각부 있거나 날짜만)는 DATE 와 같은 열 단위 순서로 해석한다 — 예전엔 시각부가 있으면 일/월만,
        // 날짜만 있으면 일/월→월/일 순 첫 성공이라 TIMESTAMP 열에서도 행마다 해석이 갈렸다(WD-246).
        LocalDateTime timestamp =
            parseDayMonthLeading(value, true, columnName, dateOrders, rowIndex);
        for (DateTimeFormatter formatter :
            timestamp != null ? List.<DateTimeFormatter>of() : TIMESTAMP_FORMATTERS) {
          try {
            timestamp = LocalDateTime.parse(value, formatter);
            break;
          } catch (DateTimeParseException ignored) {
          }
        }
        // 시간부까지 요구하는 TIMESTAMP_FORMATTERS로 실패하면, DATE_FORMATTERS(날짜만)로 재시도해
        // 자정 시각(00:00:00)을 붙인다. CSV/Excel 내보내기에서 TIMESTAMP 컬럼에 날짜만 채워 넣는
        // 경우가 흔한데, DATE 컬럼보다 더 엄격하게 거부하는 것은 실사용과 어긋난다(#516).
        if (timestamp == null) {
          for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
              timestamp = LocalDate.parse(value, formatter).atStartOfDay();
              break;
            } catch (DateTimeParseException ignored) {
            }
          }
        }
        if (timestamp == null && TIME_ONLY.matcher(value).matches()) {
          throw new Exception(TIME_ONLY_MESSAGE + value);
        }
        if (timestamp == null) {
          throw new Exception(
              "날짜시간 형식이 아닙니다: "
                  + value
                  + " (허용 형식: yyyy-MM-dd HH:mm:ss, yyyyMMddHHmmss, ISO 형식, dd/MM/yyyy HH:mm:ss,"
                  + " MM/dd/yyyy HH:mm:ss, yyyy-MM-dd 등 날짜만 있는 형식)");
        }
        yield timestamp;
      }
      case "GEOMETRY" -> {
        // 행 추가·편집 API 와 같은 규칙으로 검증·정규화한다(WD-201) — Feature 는 안의 geometry 로 바꾸고,
        // FeatureCollection·WKT 등 저장할 수 없는 값은 INSERT 에서 원인 없이 실패하기 전에 행·칸 단위 사유로 거부한다.
        try {
          yield GeoJsonValues.toStorableGeometry(value);
        } catch (IllegalArgumentException e) {
          throw new Exception(e.getMessage());
        }
      }
      default -> throw new Exception("알 수 없는 데이터 타입입니다: " + dataType);
    };
  }

  /**
   * 일·월이 앞 두 칸이고 연도가 뒤에 오는 날짜를 열 단위 순서로 해석한다(WD-246).
   *
   * <p>판정: 첫 칸 &gt; 12 이면 일/월/연도, 둘째 칸 &gt; 12 이면 월/일/연도의 근거다. 근거가 열에 이미 정해진 순서와 다르면 섞인 열 오류, 근거가
   * 없으면(둘 다 12 이하) 열에 정해진 순서(없으면 일/월/연도)를 쓴다. 날짜는 {@link LocalDate#of} 로 엄격하게 만든다 — 기본 SMART 해석기는
   * 02/31 같은 없는 날짜를 그달 말일로 바꿔 조용히 저장한다.
   *
   * @param allowTime 시각부(HH:mm:ss) 허용 여부 — TIMESTAMP 만 true
   * @return 이 형식이 아니면 null(호출자가 다른 형식을 시도). 두 칸이 모두 12 를 넘는 값도 null 로 돌려 기존 형식 오류 문구를 쓴다.
   * @throws Exception 섞인 열이거나 없는 날짜·시각일 때(한국어 사유)
   */
  private static LocalDateTime parseDayMonthLeading(
      String value, boolean allowTime, String columnName, DateOrderContext dateOrders, int rowIndex)
      throws Exception {
    java.util.regex.Matcher m = DAY_MONTH_LEADING.matcher(value);
    if (!m.matches() || (!allowTime && m.group(5) != null)) {
      return null;
    }
    int first = Integer.parseInt(m.group(1));
    int second = Integer.parseInt(m.group(3));
    int year = Integer.parseInt(m.group(4));
    if (first > 12 && second > 12) {
      return null;
    }
    DayMonthOrder evidence =
        first > 12 ? DayMonthOrder.DAY_FIRST : second > 12 ? DayMonthOrder.MONTH_FIRST : null;
    if (evidence != null) {
      dateOrders.checkConsistent(columnName, evidence, value);
    }
    DayMonthOrder order = evidence != null ? evidence : dateOrders.orderOf(columnName);
    int day = order == DayMonthOrder.DAY_FIRST ? first : second;
    int month = order == DayMonthOrder.DAY_FIRST ? second : first;
    LocalDateTime result;
    try {
      LocalDate date = LocalDate.of(year, month, day);
      result =
          m.group(5) == null
              ? date.atStartOfDay()
              : date.atTime(
                  LocalTime.of(
                      Integer.parseInt(m.group(5)),
                      Integer.parseInt(m.group(6)),
                      Integer.parseInt(m.group(7))));
    } catch (java.time.DateTimeException e) {
      throw new Exception("존재하지 않는 날짜입니다: " + value + " (" + order.label + "로 해석)");
    }
    // 실제로 있는 날짜일 때만 열 순서의 근거로 남긴다 — 잘못된 값이 열 순서를 정하지 않게.
    if (evidence != null) {
      dateOrders.record(columnName, evidence, value, rowIndex);
    }
    return result;
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
    // 이 호출 하나(샘플·단일 배치)만 보고 열의 일·월 순서를 정한다 — 임포트 잡은 아래 오버로드로 상태를 넘긴다.
    return validateWithMapping(rows, columns, mappings, rowIndexBase, new DateOrderContext());
  }

  /** {@link #validateWithMapping(List, List, List, int)} 와 같되 열의 일·월 순서 판정 상태를 호출자가 넘긴다(WD-246). */
  public ValidationResultWithDetails validateWithMapping(
      List<Map<String, String>> rows,
      List<DatasetColumnResponse> columns,
      List<ColumnMappingEntry> mappings,
      int rowIndexBase,
      DateOrderContext dateOrders) {

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
      List<Object> convertedRow =
          convertRowOrNull(remappedRow, columns, rowIndex, errors, dateOrders);
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
   * <p>Pass1 검증({@link #validateWithMapping})과 반드시 같은 셀 변환 로직({@link #convertRowOrNull})을 거치므로, "검증
   * 통과 == 값 변환 성공"이 두 경로에서 어긋나지 않는다. 매핑 없는 경로({@code mappings=null} 또는 빈 리스트)는 {@link #validate}와
   * 동일하게 컬럼명으로 행을 직접 조회한다. Pass2는 전량 검증을 통과한 배치에서만 호출되므로 변환 실패(null 반환)는 발생하지 않는 것이 전제다 — 이 전제가
   * 깨지면(변환기 불변식 위반) 빈 행을 조용히 삽입하는 대신 즉시 예외로 실패시킨다.
   */
  public List<List<Object>> toRows(
      List<Map<String, String>> rows,
      List<DatasetColumnResponse> columns,
      List<ColumnMappingEntry> mappings) {
    // 이 배치만 보고 열 순서를 정한다 — 먼저 배치 전체를 훑어 근거 값을 모은 뒤 변환해, 근거 값보다 앞의 모호한 행도 같은 순서로
    // 해석한다. 임포트 잡은 Pass1 이 파일 전체로 정한 상태를 아래 오버로드로 넘긴다.
    DateOrderContext dateOrders = new DateOrderContext();
    if (mappings != null && !mappings.isEmpty()) {
      validateWithMapping(rows, columns, mappings, 0, dateOrders);
    } else {
      validate(rows, columns, 0, dateOrders);
    }
    return toRows(rows, columns, mappings, dateOrders);
  }

  /**
   * {@link #toRows(List, List, List)} 와 같되, Pass1 검증이 파일 전체를 보고 정한 열의 일·월 순서 상태를 받는다(WD-246). 새 상태를
   * 쓰면 Pass1 이 월/일로 정한 열의 모호한 행(01/02/2026)을 Pass2 가 기본값 일/월로 해석해, 검증은 통과했는데 다른 날짜가 저장된다.
   */
  public List<List<Object>> toRows(
      List<Map<String, String>> rows,
      List<DatasetColumnResponse> columns,
      List<ColumnMappingEntry> mappings,
      DateOrderContext dateOrders) {
    boolean hasMappings = mappings != null && !mappings.isEmpty();
    Map<String, String> columnMapping = hasMappings ? buildColumnMapping(mappings) : Map.of();
    List<ValidationErrorDetail> discardedErrors = new ArrayList<>();

    List<List<Object>> result = new ArrayList<>();
    int rowIndex = 0;
    for (Map<String, String> row : rows) {
      rowIndex++;
      Map<String, String> effectiveRow = hasMappings ? remapRow(row, columnMapping) : row;
      List<Object> convertedRow =
          convertRowOrNull(effectiveRow, columns, rowIndex, discardedErrors, dateOrders);
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
   * 한 행(매핑 경로는 이미 매핑 적용됨)을 데이터셋 컬럼 순서의 값 리스트로 변환한다. 필수값 누락/타입 변환 실패는 errorSink에 기록하고 null을 반환한다(행
   * 전체 무효). {@link #validate}, {@link #validateWithMapping}, {@link #toRows} 세 경로 모두 이 메서드를 공유해 변환
   * 로직이 하나로 유지되게 한다(Task2 핵심 위험 3) — 두 벌로 갈라지면 "검증 통과 == 값 변환 성공"이 어긋나는 미묘한 버그가 생긴다.
   */
  private List<Object> convertRowOrNull(
      Map<String, String> row,
      List<DatasetColumnResponse> columns,
      int rowIndex,
      List<ValidationErrorDetail> errorSink,
      DateOrderContext dateOrders) {
    List<Object> convertedRow = new ArrayList<>();
    boolean rowValid = true;

    for (DatasetColumnResponse column : columns) {
      String rawValue = row.get(column.columnName());

      // DATE/TIMESTAMP 컬럼의 "0", "0000-00-00" 등 무의미한 날짜없음 표식은 빈 값과 동일하게 취급하여
      // 아래의 기존 빈 값 처리 경로(필수면 에러, nullable이면 null 저장)를 타게 한다.
      // "날짜 형식이 아닙니다: 0" 형태로 거부하지 않기 위함.
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
                REQUIRED_FIELD_EMPTY));
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
        // 형식 변환에 성공해도 물리 타입 제약(VARCHAR 길이·NUMERIC 정밀도·NUL 문자)을 넘으면 INSERT 단계에서 DB 오류로 잡
        // 전체가 실패하고 사용자는 행·칸을 알 수 없다(WD-96). 사전검증·임포트 검증·삽입 변환이 모두 이 메서드를 지나므로 여기서
        // 거부하고, 통과한 값은 DB 가 실제로 저장할 값으로 정규화해 넘긴다.
        Object convertedValue =
            ColumnPhysicalType.enforceLimits(
                convertValue(
                    rawValue.trim(), column.dataType(), column.columnName(), dateOrders, rowIndex),
                column.dataType(),
                column.maxLength());
        convertedRow.add(convertedValue);
      } catch (Exception e) {
        // 엑셀 오류 셀 표식은 사용자에게 엑셀이 보여 주는 모양(#DIV/0!)으로 돌려준다.
        errorSink.add(
            new ValidationErrorDetail(
                rowIndex,
                column.columnName(),
                LegacyExcelDataFormatter.toDisplay(rawValue),
                e.getMessage()));
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
                  rowIndex, pkCol, value != null ? value : "", "기본키 컬럼은 비어 있을 수 없습니다"));
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
            rowIndex
                + "행 기본키 값 중복 ("
                + String.join(", ", pkColumns)
                + ") = ("
                + compositeKey.replace("\0", ", ")
                + ") — UPSERT 모드에서는 마지막 행의 값이 적용됩니다");
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
