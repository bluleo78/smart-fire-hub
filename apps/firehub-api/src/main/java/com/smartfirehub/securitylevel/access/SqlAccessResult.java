package com.smartfirehub.securitylevel.access;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * SQL 판정 결과. 거부도 값으로 표현한다 — 차트처럼 거부를 예외가 아니라 응답 필드({@code denied})로 내보내는 호출자가 있기 때문.
 *
 * @param allowed 허용 여부
 * @param code 거부 코드(허용이면 null) — DATASET_SQL_ACCESS_DENIED 또는 SQL_WRITE_DOWNGRADE
 * @param message 거부 메시지(허용이면 null)
 * @param effectiveLevel 읽기 집합의 최대 rank 등급(테이블 없으면 null) — 파이프라인 출력 상향·S4 내보내기 판정 입력
 * @param readDatasetIds 읽기 집합이 가리키는 데이터셋 id
 * @param writeDatasetIds 쓰기 집합(DML 대상)이 가리키는 데이터셋 id
 * @param exportAllowed 읽기 집합과 쓰기 대상 전부가 EXPORT 를 허용하는가(S4 가 UI 다운로드 숨김에 쓴다) — UPDATE/DELETE ...
 *     RETURNING 이 쓰기 대상 행을 돌려주므로 쓰기 대상도 포함한다
 */
public record SqlAccessResult(
    boolean allowed,
    String code,
    String message,
    LevelPolicy effectiveLevel,
    Set<Long> readDatasetIds,
    Set<Long> writeDatasetIds,
    boolean exportAllowed) {

  /** 집합을 불변 복사본으로 고정한다 — 호출자가 결과의 데이터셋 id 집합을 바꿔 판정 결과를 오염시키지 못하게. */
  public SqlAccessResult {
    readDatasetIds = Set.copyOf(readDatasetIds);
    writeDatasetIds = Set.copyOf(writeDatasetIds);
  }

  /** 읽기 ∪ 쓰기 대상 데이터셋 id — 감사 등급 접근 기록의 대상. SQL 관문·파이프라인 게이트가 같은 합집합을 쓴다. */
  public Set<Long> touchedDatasetIds() {
    Set<Long> ids = new LinkedHashSet<>(readDatasetIds);
    ids.addAll(writeDatasetIds);
    return ids;
  }

  /** 거부 결과. 등급·데이터셋 id 는 싣지 않는다 — 거부 사유 외의 정보를 흘리지 않기 위해. */
  public static SqlAccessResult denied(String code, String message) {
    return new SqlAccessResult(false, code, message, null, Set.of(), Set.of(), false);
  }
}
