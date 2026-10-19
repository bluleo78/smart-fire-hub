package com.smartfirehub.global.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jooq.Field;

/**
 * 애드혹 SQL 결과의 컬럼 이름을 겹치지 않게 만드는 헬퍼(WD-85).
 *
 * <p><b>왜 필요한가.</b> 결과 행을 컬럼 이름 키 Map 으로 응답하므로, JOIN 의 {@code id}·{@code region} 처럼 이름이 겹치면 앞 컬럼 값이
 * 뒤 값으로 덮여 틀린 데이터가 보였다. 행을 배열로 바꾸면 웹·차트·AI 에이전트의 응답 계약이 모두 바뀌므로, 이름을 고유하게 만드는 쪽을 택했다.
 *
 * <p><b>규칙.</b> 처음 나온 이름은 그대로 두고, 두 번째부터 같은 이름에는 {@code _2}, {@code _3} … 을 붙인다. 사용자가 직접 쓴 이름(예:
 * {@code a_2})과 겹치는 번호는 건너뛴다. 겹치지 않는 쿼리의 응답은 바뀌지 않는다.
 *
 * <p><b>executor 와 같은 결과여야 한다.</b> 운영은 firehub-executor({@code
 * query_executor._unique_column_names})로 실행하므로, 두 구현은 공용 픽스처 {@code
 * fixtures/result-column-name-vectors.json} 으로 대조한다. 한쪽만 고치면 저장한 차트가 가리키는 컬럼 이름이 실행 경로마다 달라진다.
 */
public final class ResultColumnNames {

  private ResultColumnNames() {}

  /** jOOQ 결과 필드의 이름을 같은 길이·순서의 고유한 이름 목록으로 바꾼다. */
  public static List<String> unique(Field<?>[] fields) {
    return unique(Arrays.stream(fields).map(Field::getName).toList());
  }

  /** 컬럼 이름 목록을 같은 길이·순서의 고유한 이름 목록으로 바꾼다. */
  public static List<String> unique(List<String> columnNames) {
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
