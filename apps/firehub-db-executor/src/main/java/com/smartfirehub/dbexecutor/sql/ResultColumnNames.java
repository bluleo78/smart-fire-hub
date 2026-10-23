package com.smartfirehub.dbexecutor.sql;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 조회 결과의 컬럼 이름을 겹치지 않게 만든다(WD-85 규칙, WD-76 에서 {@code /execute/query} 에 적용).
 *
 * <p><b>왜.</b> 결과 행을 컬럼 이름 키 Map 으로 응답하므로 {@code SELECT a.region, b.region …} 처럼 이름이 겹치면 앞 값이 뒤 값으로
 * 덮인다. api 의 PG 경로는 {@code com.smartfirehub.global.util.ResultColumnNames.unique}, Python 실행기는
 * {@code query_executor._unique_column_names} 로 같은 문제를 막는다 — Tibero 분석 조회도 같은 규칙이어야 저장한 차트가 가리키는 컬럼
 * 이름이 백엔드마다 갈리지 않는다.
 *
 * <p><b>규칙(api 와 같음).</b> 처음 나온 이름은 그대로, 두 번째부터 같은 이름에 {@code _2}, {@code _3} … 을 붙이고, 이미 쓰였거나 원래
 * 목록에 있는 이름(사용자가 직접 쓴 {@code a_2} 등)과 겹치는 번호는 건너뛴다. api 의 공용 고정값({@code
 * fixtures/result-column-name-vectors.json})으로 {@code ResultColumnNamesParityTest} 가 대조한다. JDK 외
 * 의존성을 두지 않는다.
 */
final class ResultColumnNames {

  private ResultColumnNames() {}

  /** 컬럼 이름 목록을 같은 길이·순서의 고유한 이름 목록으로 바꾼다. */
  static List<String> unique(List<String> columnNames) {
    Set<String> original = new HashSet<>(columnNames);
    Set<String> used = new HashSet<>();
    List<String> names = new ArrayList<>(columnNames.size());
    for (String name : columnNames) {
      String unique = name;
      if (used.contains(name)) {
        int n = 2;
        // 뒤에 나올 사용자 컬럼 이름과도 겹치지 않게 원래 이름 전체를 피한다
        while (used.contains(name + "_" + n) || original.contains(name + "_" + n)) {
          n++;
        }
        unique = name + "_" + n;
      }
      used.add(unique);
      names.add(unique);
    }
    return names;
  }
}
