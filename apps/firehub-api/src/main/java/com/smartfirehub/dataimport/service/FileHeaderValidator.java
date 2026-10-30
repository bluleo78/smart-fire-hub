package com.smartfirehub.dataimport.service;

import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.exception.InvalidFileHeaderException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 임포트 파일 헤더(열 이름 목록)를 검사하는 공통 규칙(WD-205).
 *
 * <p>파서는 각 행을 「헤더 이름 → 값」 Map 으로 담으므로, 이름이 같은 열이 둘 이상이면 뒤 열 값이 앞 열을 덮어써 사용자에게 보인 매핑과 실제 저장값이 달라진다.
 * CSV·XLSX·XLS·XLSB 의 모든 헤더 읽기 지점이 이 검사를 거쳐 미리보기·사전 검증·실제 임포트가 같은 규칙을 따른다.
 *
 * <p>비교는 앞뒤 공백과 앞쪽 U+FEFF(BOM 문자)를 걷어낸 이름으로 한다. 반환되는 헤더 문자열 자체는 바꾸지 않는다. CSV 파일 앞의 BOM 은 이제 {@link
 * FileParserService} 의 CSV 리더가 읽기 단계에서 소비하므로(WD-245) 여기까지 오지 않는다. 그래도 엑셀 셀 값 자체에 U+FEFF 가 들어간 경우까지
 * 같은 이름으로 보도록 비교용 제거는 남겨 둔다.
 *
 * <p>이름이 빈 열은 몇 개든 검사하지 않고 기존 동작을 유지한다. 엑셀 CSV 내보내기에서 흔한 끝 빈 열({@code a,b,,})을 거절하면 회귀가 더 크고, 빈 이름
 * 열은 기본이 미매핑이라 겹쳐도 피해가 작기 때문이다.
 *
 * <p>다만 빈 이름 열이 둘 이상인 파일에서 그중 하나를 매핑하면, 행 Map 에서 빈 이름 키가 서로 덮어써 마지막 빈 열 값이 조용히 저장된다(WD-369). 그래서 파일
 * 자체는 받되, 빈 이름 열이 둘 이상이면 그 열들을 매핑하는 요청만 {@link #requireMappableBlankColumns}로 거절한다. 빈 이름 열이 하나뿐이면
 * 키가 겹치지 않으므로 그대로 매핑할 수 있다.
 */
final class FileHeaderValidator {

  /** UTF-8 BOM 문자(U+FEFF). 소스에 보이지 않는 문자를 직접 쓰지 않도록 코드값으로 둔다. */
  private static final char BOM = (char) 0xFEFF;

  private FileHeaderValidator() {}

  /** 배열 헤더(CSV) 검사. null 이면 검사할 헤더가 없으므로 그대로 통과한다. */
  static void requireDistinctNames(String[] headers) {
    if (headers != null) requireDistinctNames(Arrays.asList(headers));
  }

  /**
   * 이름이 있는 열 가운데 같은 이름이 둘 이상이면 {@link InvalidFileHeaderException}(400)을 던진다.
   *
   * <p>열 번호는 사용자가 보는 순서대로 1부터 센다. 겹치는 이름이 여러 개면 한 번에 모두 알려 재업로드를 한 번으로 끝내게 한다.
   */
  static void requireDistinctNames(List<String> headers) {
    if (headers == null) return;
    rejectExcelErrorNames(headers);
    if (headers.size() < 2) return;

    // 정규화한 이름 → 등장한 열 번호(1부터). 등장 순서를 유지해 메시지가 파일 순서를 따르게 한다.
    Map<String, List<Integer>> positions = new LinkedHashMap<>();
    for (int i = 0; i < headers.size(); i++) {
      String key = normalize(headers.get(i));
      if (key.isEmpty()) continue; // 빈 이름은 검사 대상 아님(위 클래스 주석 참고)
      positions.computeIfAbsent(key, k -> new ArrayList<>()).add(i + 1);
    }

    List<String> duplicated = new ArrayList<>();
    for (Map.Entry<String, List<Integer>> e : positions.entrySet()) {
      if (e.getValue().size() >= 2) {
        duplicated.add(e.getKey() + " (" + joinColumns(e.getValue()) + "번째 열)");
      }
    }
    if (duplicated.isEmpty()) return;

    throw new InvalidFileHeaderException(
        "파일에 같은 이름의 열이 있습니다: " + String.join(", ", duplicated) + ". 열 이름을 고친 뒤 다시 올려 주세요.");
  }

  /**
   * 매핑 목록에 「이름이 빈 파일 열 → 데이터셋 컬럼」이 하나라도 있는지 본다(WD-369). 헤더를 다시 읽는 비용(엑셀은 통합 문서를 다시 연다)을 이런 매핑이 있을
   * 때만 치르도록 호출부가 먼저 거르는 데 쓴다.
   */
  static boolean mapsBlankColumn(List<ColumnMappingEntry> mappings) {
    if (mappings == null) return false;
    return mappings.stream().anyMatch(FileHeaderValidator::isMappedBlank);
  }

  /**
   * 빈 이름 파일 열을 데이터셋 컬럼에 매핑한 항목인지(WD-369). 검증·적재({@link DataValidationService})와 같이 datasetColumn 이 있으면
   * 매핑된 것이다.
   */
  private static boolean isMappedBlank(ColumnMappingEntry m) {
    return m != null && m.datasetColumn() != null && normalize(m.fileColumn()).isEmpty();
  }

  /**
   * 이름이 빈 열이 둘 이상인 파일에서 빈 이름 열을 매핑하면 {@link InvalidFileHeaderException}(400)을 던진다(WD-369).
   *
   * <p>행은 「헤더 이름 → 값」 Map 이라 이름이 같은 빈 열들이 같은 키("")를 나눠 쓰고, 마지막 빈 열 값만 남는다. 어느 빈 열을 가리킨 매핑인지 서버가
   * 구분할 수 없으므로 경고 없이 엉뚱한 값이 저장되지 않게 거절한다. 매핑한 빈 열과 키가 같은 열이 하나뿐이거나, 빈 이름 열을 매핑하지 않았으면 통과한다(끝 빈
   * 열 {@code a,b,,} 파일은 계속 받는다).
   */
  static void requireMappableBlankColumns(List<String> headers, List<ColumnMappingEntry> mappings) {
    if (headers == null || mappings == null) return;

    for (ColumnMappingEntry m : mappings) {
      if (!isMappedBlank(m)) continue;
      // 행 Map 의 키는 다듬지 않은 헤더 그대로다. 그래서 매핑한 빈 열과 글자까지 같은 헤더가 둘 이상일 때만 값이 덮어써진다
      // ("" 와 " " 는 다른 키라 겹치지 않는다).
      List<Integer> sameKeyColumns = new ArrayList<>();
      for (int i = 0; i < headers.size(); i++) {
        if (Objects.equals(headers.get(i), m.fileColumn())) sameKeyColumns.add(i + 1);
      }
      if (sameKeyColumns.size() >= 2) throw blankColumnsConflict(sameKeyColumns);
    }
  }

  /** 겹치는 빈 이름 열 번호(1부터)로 사용자에게 보일 거절 사유를 만든다(WD-369). */
  private static InvalidFileHeaderException blankColumnsConflict(List<Integer> blankColumns) {
    return new InvalidFileHeaderException(
        "이름이 비어 있는 열이 "
            + blankColumns.size()
            + "개라 어느 열을 가져올지 구분할 수 없습니다 ("
            + joinColumns(blankColumns)
            + "번째 열). 열 이름을 넣은 뒤 다시 올려 주세요.");
  }

  /**
   * 엑셀 헤더 셀이 오류 셀(#REF! 등)이면 열 이름으로 쓸 수 없으므로 거부한다(WD-252). 파서가 오류 셀을 표식으로 넘기는데, 그대로 두면 표식(NUL 포함)이
   * 열 이름으로 미리보기·매핑에 보인다.
   */
  private static void rejectExcelErrorNames(List<String> headers) {
    List<String> errors = new ArrayList<>();
    for (int i = 0; i < headers.size(); i++) {
      String code = LegacyExcelDataFormatter.errorCodeOf(headers.get(i));
      if (code != null) errors.add((i + 1) + "번째 열 " + code);
    }
    if (errors.isEmpty()) return;
    throw new InvalidFileHeaderException(
        "헤더 행에 엑셀 오류 값이 있습니다: " + String.join(", ", errors) + ". 열 이름을 고친 뒤 다시 올려 주세요.");
  }

  /** 비교용 이름: 앞쪽 BOM 과 앞뒤 공백을 제거한다. null 은 빈 이름으로 본다. */
  private static String normalize(String name) {
    if (name == null) return "";
    String s = !name.isEmpty() && name.charAt(0) == BOM ? name.substring(1) : name;
    return s.strip();
  }

  /** [1, 2] → "1·2" */
  private static String joinColumns(List<Integer> cols) {
    return cols.stream().map(String::valueOf).collect(Collectors.joining("·"));
  }
}
