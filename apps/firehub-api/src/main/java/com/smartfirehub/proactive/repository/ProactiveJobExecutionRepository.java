package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionSummaryResponse;
import com.smartfirehub.proactive.dto.ReportListItemResponse;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 프로액티브 잡 실행 이력 저장소.
 *
 * <p>구현은 {@code JooqProactiveJobExecutionRepository}(jOOQ/PostgreSQL) 이다 — WD-485. 다루는 테이블: {@code
 * proactive_job_execution}, {@code proactive_job}, {@code security_level}, {@code dataset}(등급 조회) 와
 * DEFINER 함수 {@code proactive_active_execution_tenant_ids()}.
 */
public interface ProactiveJobExecutionRepository {

  Long create(Long jobId);

  boolean updateStatus(Long id, String status, LocalDateTime startedAt, LocalDateTime completedAt);

  boolean updateResult(
      Long id, String status, Map<String, Object> result, LocalDateTime completedAt);

  boolean updateError(Long id, String errorMessage);

  /** 등록된 활성 실행만 하트비트를 갱신한다. RLS 때문에 호출자는 테넌트 컨텍스트 안에 있어야 한다. */
  int touchHeartbeat(Collection<Long> executionIds, LocalDateTime now);

  /**
   * 활성(PENDING·RUNNING) 실행이 있는 테넌트 id 목록(WD-258) — 정리기가 매분 모든 테넌트를 돌지 않고 이 목록만 돈다. V149 의 SECURITY
   * DEFINER 함수({@code outbox_tenant_ids} 와 같은 모양)라 테넌트 컨텍스트 없이도 RLS 를 넘어 정수 목록만 받는다. 실제 정리는 호출자가
   * 테넌트마다 컨텍스트를 연 뒤 RLS 아래서 한다. 실행 표에서 유도하므로 정지된 워크스페이스의 고아 실행도 빠지지 않는다.
   *
   * <p>반환은 완전히 구체화된 리스트다 — 호출자({@code sweep}, 트랜잭션 없음)가 이 메서드를 마친 뒤 테넌트별로 새 트랜잭션을 열어야 각자의 GUC 가
   * 들어간다.
   */
  List<Long> tenantIdsWithActiveExecutions();

  /** 오래된 활성 실행 ID만 조회한다. 완료·실패 행과 다른 테넌트 행은 제외한다. */
  List<Long> findStaleExecutions(long tenantId, LocalDateTime threshold);

  List<ProactiveJobExecutionResponse> findByJobId(Long jobId, int limit, int offset);

  /**
   * 실행 이력 목록(경량 뷰) 조회 — 컨트롤러 목록 endpoint 전용 (#604).
   *
   * <p>{@link #findByJobId}와 달리 {@code result}(리포트 본문) 컬럼 자체를 SELECT하지 않는다. 목록은 실행 건수만큼 반복되므로 본문을
   * 포함하면 응답이 수만 자에 달해 MCP 도구 결과 토큰 한도를 초과할 수 있다. 본문이 필요하면 단건 조회({@link #findById})를 사용해야 한다.
   */
  List<ProactiveJobExecutionSummaryResponse> findSummariesByJobId(
      Long jobId, int limit, int offset);

  /**
   * 잡마다 가장 최근 실행 한 건을 읽는다 — 잡 목록·상세의 「마지막 실행 상태」용 (WD-134).
   *
   * <p>예전에는 잡 응답의 {@code lastExecution} 을 채우는 곳이 없어 늘 null 이었고, 예약 실행이 계속 실패해도 목록·상세 어디에도 실패가 드러나지
   * 않았다. 목록은 잡 수만큼 반복되므로 {@code result}(리포트 본문)는 읽지 않는다({@link #findSummariesByJobId} 와 같은 이유) —
   * 돌려주는 응답의 {@code result} 는 항상 null 이다.
   *
   * @return 잡 ID → 최근 실행. 실행 이력이 없는 잡은 키가 없다
   */
  Map<Long, ProactiveJobExecutionResponse> findLatestByJobIds(Collection<Long> jobIds);

  Optional<ProactiveJobExecutionResponse> findById(Long id);

  /** 실행 완료 후 실제 전달된 채널 목록을 DB에 저장한다. 쉼표 구분 문자열로 저장 (예: "CHAT,EMAIL"). */
  void updateDeliveredChannels(Long executionId, List<String> channels);

  /**
   * 사용자가 소유한 스마트 작업들이 생성한 리포트를 잡 횡단으로 조회한다.
   *
   * <p>"리포트"는 COMPLETED 이면서 result.htmlContent 가 실제로 있는 실행만을 뜻한다. htmlContent 가 없으면 뷰어가 404를 내므로
   * 목록에 넣으면 빈 화면으로 이어진다.
   *
   * <p>본문(htmlContent)은 수십 KB이므로 SELECT 하지 않고 WHERE 절의 존재 판정에만 사용한다. 잡 스코핑이 없는 엔드포인트이므로 user_id 조건이
   * 유일한 소유권 방어선이다.
   */
  List<ReportListItemResponse> findReportsByUserId(Long userId, int limit, int offset);

  /**
   * 리포트 등급을 올린다(WD-198) — 지금 등급이 없거나 rank 가 더 낮을 때만 바꾼다(「가장 높은 등급」만 남는다). 한 문장 UPDATE 라 동시 기록끼리 낮은
   * 값으로 덮어쓰지 않는다.
   *
   * <p>헤더 위조 방지 조건(대행 요청 경로, {@code runningSince != null}): 실행의 작업 작성자가 {@code ownerUserId} 이고, 요청
   * 시작 시점에 진행 중이었던 실행(지금 RUNNING 이거나 그 이후에 끝남)일 때만 갱신한다. 조건에 안 맞으면 0행 — 호출부는 조용히 무시한다. 실행기 내부
   * 경로({@code runningSince == null})도 작성자 조건은 그대로 건다.
   *
   * <p>다른 테넌트의 등급 id 는 RLS 로 security_level 이 보이지 않아 rank 비교가 NULL → 갱신되지 않는다(복합 FK 도 거부한다).
   *
   * @return 갱신된 행 수(0 또는 1)
   */
  int raiseSecurityLevel(
      long executionId, long ownerUserId, long levelId, LocalDateTime runningSince);

  /** 데이터셋들의 등급 id(중복 제거) — 리포트 등급 기록이 결과에 실린 데이터셋 id 로부터 등급을 구할 때 쓴다(WD-198). RLS 로 현재 테넌트만 보인다. */
  Set<Long> findDatasetLevelIds(Collection<Long> datasetIds);

  /** 등급별 리포트(실행) 수 — 등급 삭제의 「사용 중」 판정(WD-198). */
  Map<Long, Long> countByLevel();

  /** 등급 삭제 시 그 등급의 리포트를 옮길 등급으로 옮긴다(WD-198) — 복합 FK 위반을 막고 리포트가 등급 없음(열림)으로 풀리지 않게. */
  int moveLevel(long fromLevelId, long toLevelId);
}
