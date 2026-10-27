package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.QueryResult;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Tibero 테넌트 증분 책갈피 후보값을 Tibero 시계로 캡처한다(WD-77 Task 10). PG 의 {@link IncrementalCursorService} 에
 * 대응한다.
 *
 * <p>{@code _UPDATED_AT} 은 Tibero 서버 시계({@code SYSTIMESTAMP})로 채워지므로 책갈피도 같은 시계에서 잡아야 한다(PG 시계와 섞지
 * 않는다). 후보 = {@code LEAST(SYSTIMESTAMP, 열려 있는 가장 오래된 트랜잭션 시작 시각)}: 아직 커밋되지 않은 트랜잭션이 나중에 커밋하는 행의
 * {@code _UPDATED_AT} 은 그 트랜잭션이 쓴 시각 이후라서, 후보를 그 트랜잭션 시작 이하로 잡아야 다음 실행이 놓치지 않는다. 호출자는 SQL 실행
 * <b>전에</b> 불러야 한다(실행 후에 잡으면 실행 도중 커밋된 행을 영원히 건너뛴다).
 *
 * <p>조회는 RO 계정 창구({@link DbExecutorClient#executeQuery})로 한 문장에 끝낸다 — 시각과 트랜잭션을 따로 읽으면 그 사이 시작한
 * 트랜잭션이 양쪽에서 빠진다. 실서버로 확인한 사실:
 *
 * <ul>
 *   <li>공개 동의어 {@code V$TRANSACTION} 은 일반 계정에서 풀리지 않는다(JDBC-8033). 고정 테이블 {@code
 *       SYS._VT_TRANSACTION} 을 읽는다 (db-executor 가드가 이 이름 하나만 허용한다).
 *   <li>{@code START_TIME} 은 DATE(초 단위, 서버 시계)다. 초 아래가 잘려 더 이른 값이 되므로 후보가 이르게 잡힐 뿐 안전하다. 서버 <b>지역
 *       시간대</b>를 {@code TO_CHAR(SYSTIMESTAMP, 'TZR')} 로 붙여(서버 시간대 가정 없이) UTC 로 바꾼다(WD-234). 지역 이름이면
 *       그 시작 시각의 오프셋을 써서, 서머타임 전환을 가로질러 열려 있던 트랜잭션도 맞게 바뀐다(예전 현재 오프셋 {@code TZH:TZM} 은 1시간 어긋났다).
 *       오프셋 문자열({@code +09:00})을 주는 서버에서도 {@code FROM_TZ} 가 받는다(실서버 확인).
 *   <li>결과는 문자열({@code YYYY-MM-DD HH24:MI:SS.FF6}, UTC)로 받는다 — 시간대 있는 타입의 JSON 직렬화 형식에 기대지 않는다.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TiberoIncrementalCursor {

  /** 후보 조회 문장. db-executor 실서버 테스트(TiberoLiveCursorTest)가 같은 문장으로 검증한다. */
  static final String CANDIDATE_SQL =
      "SELECT TO_CHAR(LEAST(SYSTIMESTAMP, NVL(FROM_TZ(CAST(MIN(START_TIME) AS TIMESTAMP), "
          + TiberoTimestamps.SERVER_ZONE_SQL
          + "), SYSTIMESTAMP)) AT TIME ZONE '+00:00',"
          + " 'YYYY-MM-DD HH24:MI:SS.FF6') AS CANDIDATE FROM SYS._VT_TRANSACTION";

  private static final String FAILURE_PREFIX = "Tibero 증분 커서 후보를 읽지 못했습니다";

  private final DbExecutorClient dbExecutorClient;

  /**
   * 후보값(UTC)을 Tibero 에서 읽는다.
   *
   * @throws ScriptExecutionException 조회 실패·빈 결과·해석 불가 — 후보 없이 증분을 돌리면 행을 놓칠 수 있어 단계를
   *     실패시킨다(fail-closed).
   */
  public OffsetDateTime captureCandidate() {
    QueryResult result = dbExecutorClient.executeQuery(CANDIDATE_SQL, 1);
    if (!result.success()) {
      throw new ScriptExecutionException(
          FAILURE_PREFIX + ": " + (result.error() == null ? "원인 불명" : result.error()));
    }
    if (result.rows().isEmpty()) {
      throw new ScriptExecutionException(FAILURE_PREFIX + ": 결과가 비어 있습니다.");
    }
    Map<String, Object> row = result.rows().get(0);
    // 컬럼명은 실행기가 소문자로 정규화한다 — 별칭이 하나뿐이라 첫 값을 쓴다.
    Object value = row.values().stream().findFirst().orElse(null);
    try {
      return LocalDateTime.parse(String.valueOf(value), TiberoTimestamps.UTC_MICROS)
          .atOffset(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      throw new ScriptExecutionException(FAILURE_PREFIX + ": 시각 형식을 해석할 수 없습니다(" + value + ").");
    }
  }
}
