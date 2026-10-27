package com.smartfirehub.pipeline.service.step;

import com.smartfirehub.pipeline.service.PythonScriptExecutor;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 파이프라인 스텝 실행에서 저장소 백엔드(PG·Tibero)마다 갈리는 작은 분기를 한곳에 모은 방언(WD-231, 스펙 §3.2).
 *
 * <p>예전에는 {@code PipelineAsyncRunner} 가 {@code tiberoTenant} 불리언으로 줄마다 분기했다. 분기 본문을 그대로 두 구현(
 * {@link PgStepDialect}·{@link TiberoStepDialect})으로 옮기고 러너는 {@code dialectFor(backend)} 로 호출만 한다 —
 * 동작은 바뀌지 않는다. 스프링 빈이 아니다: 러너가 자기 필드(협력 객체)로 스텝마다 만든다.
 *
 * <p><b>이 인터페이스에 없는 것(의도적).</b>
 *
 * <ul>
 *   <li>PG 열 탐침({@code SqlColumnProbe})·PG SQL 실행({@code ExecutorClient.executeSql}, {@code
 *       SqlScriptExecutor}) — {@code SqlGateArchitectureTest} 가 PG 싱크 호출을 보안 판정({@code
 *       checkStepSqlForRun})과 같은 메서드({@code executeStep})에 두도록 강제한다. 방언으로 옮기면 판정과 실행이 갈라진다. 그래서 SQL
 *       실행에 쓰는 메서드는 구현별 공개 메서드다 — Tibero 탐침·실행 본체({@link TiberoStepDialect#probeTibero}·{@link
 *       TiberoStepDialect#executeSql}), PG 래핑 조립·실패 번역({@link PgStepDialect#wrapSelect}·{@code
 *       translate*}). 러너가 방언의 구체 타입으로 골라 부르고, PG 싱크는 그 사이에서 직접 부른다.
 *   <li>{@code isReplace} 해석 — 경로(PYTHON 실행기 켬/끔, API_CALL)마다 의도적으로 다르다(원본 문자열 vs 해석된 enum). 방언은
 *       호출자가 해석한 값을 받는다.
 * </ul>
 */
public interface StepDialect {

  /** 증분 후보 시각 — PG 는 IncrementalCursorService, Tibero 는 TiberoIncrementalCursor. */
  OffsetDateTime captureIncrementalCandidate();

  /** REPLACE·전체 재생성 비우기 문장(같은 트랜잭션으로 보낼 선행문). */
  String outputClearStatement(String outputTableName);

  /**
   * 사용자 DML·증분 실행 전 보장(Tibero 행 트리거). PG 는 아무것도 안 한다. 보안 게이트·출력 등급 처리가 끝난 뒤 불러야 거부된 SQL 이 DDL 을 만들지
   * 않는다.
   */
  void prepareSqlExecution(
      String sql, boolean isSelect, boolean incrementalStep, String outputTableName);

  /** APPEND 도 스테이징을 거치는가(Tibero 만 true). */
  boolean stagesAppend(boolean isReplace, String outputTable);

  /** Python 스크립트 토큰(PG 는 null). */
  String issueScriptToken(long tenantId, int readSlot, Duration ttl);

  /** 실행기 꺼짐 Python 실행. */
  PythonScriptExecutor.RunResult runLocalPython(String script, int readSlot, String scriptToken);

  /** 실행기 꺼짐 Python 적재(PG 는 트랜잭션 적재, Tibero 는 스테이징 창구). 적재한 행 수를 돌려준다. */
  long loadLocalPythonOutput(String targetTable, String stdout, Map<String, String> columnTypes);

  /** 로컬 REPLACE 에 PG 출력 세션 잠금을 잡는가(PG REPLACE 만 true). */
  boolean takesOutputSessionLock(boolean isReplace);
}
