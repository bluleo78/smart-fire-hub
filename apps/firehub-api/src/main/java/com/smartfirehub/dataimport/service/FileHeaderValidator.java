package com.smartfirehub.dataimport.service;

import com.smartfirehub.dataimport.exception.InvalidFileHeaderException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    if (headers == null || headers.size() < 2) return;

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
