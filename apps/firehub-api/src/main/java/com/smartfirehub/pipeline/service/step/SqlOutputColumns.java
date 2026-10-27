package com.smartfirehub.pipeline.service.step;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL 단계 SELECT 적재의 출력 컬럼 맞춤 규칙 — PG·Tibero 두 방언과 러너(TEMP 생성의 예약어 별칭)가 함께 쓴다(WD-231).
 *
 * <p>예전에는 {@code PipelineAsyncRunner} 의 private static 도우미였다. 실행 본체를 방언으로 옮기면서 두 방언이 같은 규칙을 써야 해서 한
 * 곳에 모았다 — 복사본을 두면 한쪽만 바뀌어 PG·Tibero 의 맞춤 결과가 갈라진다.
 */
public final class SqlOutputColumns {

  private SqlOutputColumns() {}

  /** 출력 데이터셋 컬럼 이름 집합(메타데이터 이름). */
  public static Set<String> outputColumnNames(List<DatasetColumnResponse> outputColumns) {
    return outputColumns.stream()
        .map(DatasetColumnResponse::columnName)
        .collect(Collectors.toSet());
  }

  /**
   * SELECT 결과 컬럼 중 출력 데이터셋에 있는 것만 적재 대상으로 고른다(PG·Tibero 공용). 하나도 없으면 무엇을 맞춰야 하는지 안내하고 실패시킨다.
   *
   * @param selectColumns 맞춤에 쓸 결과 컬럼(메타데이터 이름)
   * @param shownColumns 실패 안내에 보여 줄 결과 컬럼(PG 는 맞춤 이름 그대로, Tibero 는 탐지된 원래 이름)
   */
  public static List<String> matchOutputColumns(
      List<String> selectColumns, List<String> shownColumns, Set<String> outputColumnNames) {
    List<String> matchedColumns =
        selectColumns.stream().filter(outputColumnNames::contains).toList();
    if (matchedColumns.isEmpty()) {
      throw new ScriptExecutionException(
          "SELECT 결과 컬럼이 출력 데이터셋의 컬럼과 일치하지 않습니다. "
              + "SELECT alias를 출력 테이블 컬럼명과 맞춰주세요. "
              + "SELECT 컬럼: "
              + shownColumns
              + ", 출력 테이블 컬럼: "
              + outputColumnNames);
    }
    return matchedColumns;
  }

  /**
   * MERGE 병합 키 = 출력 데이터셋의 기존 PK 컬럼(PG·Tibero 공용). 저장 시점에 검증됐어도 이후 스키마가 바뀌었을 수 있어(컬럼 삭제 등) 실행 시점에 다시
   * 확인한다 — 조용히 APPEND 로 격하되면 안 되므로 없으면 명확히 실패시킨다.
   */
  public static List<String> requireMergePk(List<DatasetColumnResponse> outputColumns) {
    List<String> pk =
        outputColumns.stream()
            .filter(DatasetColumnResponse::isPrimaryKey)
            .map(DatasetColumnResponse::columnName)
            .toList();
    if (pk.isEmpty()) {
      throw new ScriptExecutionException("MERGE 에는 출력 데이터셋 PK 컬럼이 필요합니다(데이터셋 스키마가 변경되었는지 확인하세요).");
    }
    return pk;
  }

  /**
   * SELECT 결과 컬럼명 중 시스템 예약어(id/import_id/created_at/_updated_at)와 충돌하는 이름을 자동으로 안전한 이름으로 바꾼다(#645).
   *
   * <p>{@code SELECT * FROM {{#N}}}처럼 이전 스텝(또는 실제 데이터셋)의 출력을 그대로 재사용하면, 모든 데이터셋 물리 테이블이 자동으로 갖는 시스템
   * 컬럼(id/created_at 등)이 결과 컬럼에 그대로 섞여 들어온다. 이를 새 임시 데이터셋의 "사용자 컬럼"으로 그대로 저장하려 하면 {@code
   * DataTableService}의 예약어 가드에 걸려 항상 실패한다. 그 가드는 사용자가 신규 데이터셋을 만들 때 컬럼명을 직접 예약어로 짓는 것을 막기 위한 것이라 이
   * 자동 패스스루 시나리오에는 부적합하므로, 여기서는 충돌하는 컬럼명에 순번 접미사를 붙여 자동으로 별칭 처리한다 (예: {@code id} → {@code id_1},
   * {@code _updated_at} → {@code updated_at_1}).
   *
   * <p><b>선행 밑줄을 반드시 떼고 접미사를 붙인다(코드리뷰 HIGH).</b> {@code DataTableService.validateName} 은 컬럼명마다
   * {@code ^[a-z][a-z0-9_]*$} 를 요구한다 — 밑줄로 시작하는 이름은 거부다. 그래서 {@code _updated_at} 을 단순히 {@code
   * _updated_at_1} 로 바꾸면 여전히 무효라 임시 데이터셋 생성이 {@code InvalidTableNameException} 으로 실패한다. V124 백필이
   * <b>모든</b> 데이터셋 테이블에 {@code _updated_at} 을 추가했으므로 {@code SELECT *} 스텝은 100% 이 경로를 탄다. 대소문자도 함께
   * 정규화한다 — 검증 정규식이 소문자만 허용하기 때문이다.
   *
   * <p>목록의 <b>개수와 순서는 절대 바꾸지 않는다</b> — 같은 목록이 뒤에서 INSERT 대상 컬럼 매칭에 그대로 재사용되므로, 한 컬럼이라도 빠지면 SELECT 식
   * 목록과 어긋나 실행이 깨진다.
   *
   * @param names SELECT 결과 컬럼명 목록 (순서 보존 필요 — INSERT 매칭에 그대로 재사용됨)
   * @return 예약어 충돌이 해소된 컬럼명 목록 (같은 순서, 같은 개수)
   */
  public static List<String> renameReservedColumnNames(List<String> names) {
    Set<String> used = new HashSet<>(names);
    List<String> result = new ArrayList<>();
    for (String name : names) {
      String candidate = name;
      // 예약 컬럼 집합은 DataTableService 와 공유한다 — 따로 복사해 두면 시스템 컬럼이 하나 늘었을 때
      // 이쪽만 조용히 낡는다(실제로 _updated_at 이 그렇게 추가됐다).
      if (DataTableService.SYSTEM_COLUMNS.contains(name.toLowerCase())) {
        // 접미사를 붙일 기반 이름 — 선행 밑줄 제거 + 소문자화로 항상 ^[a-z][a-z0-9_]*$ 를 만족시킨다.
        // (예약어 집합이 전부 영문자로 시작하므로 밑줄을 떼면 반드시 [a-z] 로 시작한다. 그래도
        // 방어적으로 빈 문자열이면 "col" 로 대체한다.)
        String base = name.toLowerCase().replaceFirst("^_+", "");
        if (base.isEmpty()) {
          base = "col";
        }
        int suffix = 1;
        candidate = base + "_" + suffix;
        // 사용자 컬럼에 이미 그 이름이 있으면 번호를 올려 충돌을 피한다(원래 이름 집합 + 이미 만든 별칭).
        while (used.contains(candidate)) {
          suffix++;
          candidate = base + "_" + suffix;
        }
      }
      used.add(candidate);
      result.add(candidate);
    }
    return result;
  }
}
