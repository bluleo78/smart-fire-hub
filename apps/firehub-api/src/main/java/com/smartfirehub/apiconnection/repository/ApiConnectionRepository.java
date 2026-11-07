package com.smartfirehub.apiconnection.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * API 인증정보(api_connection) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqApiConnectionRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface ApiConnectionRepository {

  /**
   * API 연결 한 행. 서비스가 응답 변환·감사 스냅샷·인증 정보 복호화에 읽는 열 전부를 담는다(WD-485 — 서비스가 jOOQ Record 를 다루지 않게 리포지토리가
   * 도메인 값으로 돌려준다). {@code authConfig} 는 암호문 그대로다.
   */
  record ApiConnectionRow(
      Long id,
      String name,
      String authType,
      String authConfig,
      String description,
      Long createdBy,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      String baseUrl,
      String healthCheckPath,
      String lastStatus,
      LocalDateTime lastCheckedAt,
      Long lastLatencyMs,
      String lastErrorMessage) {}

  /** 헬스체크 대상 — 주기 점검이 대상마다 부르는 데 쓰는 id·이름만 담는다. */
  record HealthCheckTarget(Long id, String name) {}

  /** 이 연결을 참조하는 파이프라인(삭제 전 영향 범위 확인용, #605). */
  record ReferencingPipeline(Long id, String name) {}

  /** API 연결 전체 목록을 조회한다. */
  List<ApiConnectionRow> findAll();

  /** id 로 API 연결 한 건을 조회한다. 없으면 빈 값. */
  Optional<ApiConnectionRow> findById(Long id);

  /** 특정 사용자가 만든 API 연결 목록을 조회한다. */
  List<ApiConnectionRow> findByCreatedBy(Long userId);

  /** API 연결을 저장한다. baseUrl은 필수, healthCheckPath는 선택. */
  Long save(
      String name,
      String description,
      String authType,
      String encryptedAuthConfig,
      Long createdBy,
      String baseUrl,
      String healthCheckPath);

  /**
   * API 연결을 갱신한다. null인 항목은 Map에서 제외하여 기존 값을 보존한다.
   *
   * @param baseUrl 변경할 Base URL (null이면 미변경)
   * @param healthCheckPath 변경할 헬스체크 경로 (null이면 미변경)
   */
  void update(
      Long id,
      String name,
      String description,
      String authType,
      String encryptedAuthConfig,
      String baseUrl,
      String healthCheckPath);

  /**
   * 헬스체크 결과를 DB에 반영하고 <b>바꾸기 직전의 상태</b>를 돌려준다. last_checked_at은 현재 시각으로 자동 설정된다.
   *
   * <p><b>왜 직전 상태를 여기서 돌려주나(WD-189)</b>: 상태 전환 알림은 "직전 상태 ≠ 새 상태"로 판단한다. 예전에는 주기 점검이 순회 시작 때 읽어 둔
   * 값과 비교해, 그 사이 「테스트」가 상태를 먼저 바꿔 두면 전환이 알림 없이 사라졌다. 읽기와 쓰기를 같은 트랜잭션에서 행 잠금({@code FOR UPDATE})으로
   * 묶어야, 점검·테스트·새로고침이 같은 연결을 동시에 바꿔도 각 전환을 정확히 한 호출만 본다(잠금 없이 읽으면 두 호출이 같은 커밋된 값을 읽어 알림이 두 번 난다).
   * PostgreSQL 16 에는 {@code RETURNING OLD} 가 없어 잠금 조회 + 갱신 두 문장으로 한다. 클래스 레벨 {@code @Transactional}
   * 이 두 문장을 한 트랜잭션으로 묶는다.
   *
   * @param id API 연결 ID
   * @param status 헬스체크 상태 (예: "UP", "DOWN")
   * @param latencyMs 응답 지연 ms (오류 시 null)
   * @param errorMessage 오류 메시지 (정상 시 null)
   * @return 갱신 직전의 last_status. 첫 점검이었거나(null) 그 사이 연결이 지워져 갱신할 행이 없으면 null — 둘 다 알릴 전환이 아니다.
   */
  String updateHealthStatus(Long id, String status, Long latencyMs, String errorMessage);

  /**
   * 헬스체크 대상 API 연결 목록을 반환한다. health_check_path가 설정된 레코드만 포함한다. 조회 열은 예전 그대로 두고(SQL 불변) 호출자가 읽는
   * id·이름만 돌려준다.
   */
  List<HealthCheckTarget> findHealthCheckable();

  /**
   * 동일 이름의 API 연결이 이미 존재하는지 확인한다 (#647). RLS(V96) 정책이 현재 트랜잭션의 {@code app.tenant_id} GUC로 자동 필터링하므로
   * 여기서 tenant_id를 명시적으로 조건에 넣을 필요가 없다 — 이미 pipeline.name 유니크 인덱스(V93)에서 검증된 것과 동일한 전제다.
   */
  boolean existsByName(String name);

  /** 이름 중복 확인 시 자기 자신(id)은 제외한다 — update()에서 이름을 바꾸지 않는 경우 자기 자신과 충돌 판정되는 것을 방지. */
  boolean existsByNameExcludingId(String name, Long excludeId);

  /**
   * 이 id 의 API 연결이 현재 워크스페이스에서 보이는지 확인한다(WD-176). {@link #existsByName} 과 같이 RLS(V96) 정책이 {@code
   * app.tenant_id} GUC 로 걸러 주므로, 다른 워크스페이스의 연결과 없는 연결은 똑같이 {@code false} 다. 외래키 검사는 RLS 를 우회해 다른
   * 워크스페이스 행도 통과시키므로, 참조를 저장하기 전에는 반드시 이 조회로 확인해야 한다.
   */
  boolean existsById(Long id);

  /**
   * id 로 API 연결을 삭제한다.
   *
   * @return 지운 행 수 — 동시 삭제에서 진 쪽은 0 이라 호출자가 404 로 바꾼다(WD-584)
   */
  int deleteById(Long id);

  /**
   * 이 API 연결을 사용하는(pipeline_step.api_connection_id로 참조하는) 파이프라인 목록을 조회한다. 삭제 전 영향 범위 확인용(#605).
   * DISTINCT로 같은 파이프라인의 여러 API_CALL 스텝이 동일 연결을 참조하는 경우 중복을 제거한다.
   */
  List<ReferencingPipeline> findReferencingPipelines(Long apiConnectionId);
}
