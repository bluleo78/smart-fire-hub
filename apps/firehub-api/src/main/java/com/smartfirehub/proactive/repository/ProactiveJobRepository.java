package com.smartfirehub.proactive.repository;

import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 프로액티브 잡 저장소.
 *
 * <p>구현은 {@code JooqProactiveJobRepository}(jOOQ/PostgreSQL) 이다 — WD-485. 다루는 테이블: {@code
 * proactive_job}, {@code report_template}, {@code "user"}.
 */
public interface ProactiveJobRepository {

  List<ProactiveJobResponse> findByUserId(Long userId);

  Optional<ProactiveJobResponse> findById(Long id, Long userId);

  Optional<ProactiveJobResponse> findById(Long id);

  /**
   * 지정한 테넌트의 활성 잡을 전부 읽는다 (부팅 시 크론 재등록 전용).
   *
   * <p><b>왜 RLS 에 맡기지 않고 {@code tenant_id} 술어를 명시하는가</b>: 호출자 ({@code
   * ProactiveJobSchedulerService.reloadAllSchedules})는 ACTIVE 테넌트를 순회하며 잡마다 발화 시점 테넌트를 캡처한다.
   * V104(정책) 이전에는 이 조회가 컨텍스트와 무관하게 전 테넌트 행을 돌려주므로, 술어가 없으면 <b>모든 잡이 마지막으로 순회된 테넌트를 캡처</b>해 남의 테넌트로
   * 발화한다. 정책이 켜진 뒤에는 같은 결과를 두 번 보장하는 방어적 중복이 된다.
   */
  List<ProactiveJobResponse> findAllEnabled(long tenantId);

  /**
   * Proactive Job을 새로 생성한다.
   *
   * <p>{@code enabled}가 null이면 기본값 {@code true}를 적용한다. 호출자가 {@code false}를 명시하면 비활성 상태로 저장된다
   * (#220).
   */
  Long create(
      Long userId,
      String name,
      String prompt,
      Long templateId,
      String cronExpression,
      String timezone,
      Boolean enabled,
      String triggerType,
      Map<String, Object> config);

  /**
   * 수정 시각을 행 잠금(FOR UPDATE)과 함께 읽는다 — 편집 충돌(낙관적 잠금) 비교용(WD-364, 파이프라인 WD-353 과 같은 방식).
   *
   * <p>잠금 없이 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠금을 잡으면 나중 저장은 앞 저장이 커밋될 때까지 기다렸다가 바뀐 값을 읽어
   * 충돌로 거부된다. 소유자 확인을 마친 뒤에 부른다 — 먼저 부르면 남의 행에 대한 낡은 요청이 404 대신 409 를 받아 존재가 드러난다.
   *
   * <p>행이 없으면 빈 값, 행은 있는데 수정 시각이 비어 있으면(nullable 컬럼, V42) {@code updatedAt} 이 null 인 {@link
   * LockedRowVersion} 이다 — 둘을 구분해야 수정 시각이 빈 옛 행을 "없음"으로 잘못 거부하지 않는다.
   */
  Optional<LockedRowVersion> lockUpdatedAtById(Long id);

  /**
   * 작업을 고치고 새로 저장된 수정 시각을 돌려준다(WD-364).
   *
   * <p>Java 값이 아니라 DB 에 저장된 값(RETURNING)을 돌려준다 — PG timestamp 는 마이크로초로 반올림하므로 넣은 값을 그대로 돌려주면 다음 저장의
   * 기준 버전이 저장값과 어긋나 자기 저장과 충돌한다. 고친 행이 없으면(남의 작업) null.
   */
  LocalDateTime update(
      Long id,
      Long userId,
      String name,
      String prompt,
      Long templateId,
      boolean clearTemplate,
      String cronExpression,
      String timezone,
      Boolean enabled,
      String triggerType,
      Map<String, Object> config);

  /**
   * 실행 기록(마지막 실행·다음 실행 예정)을 갱신한다.
   *
   * <p>{@code updated_at} 은 갱신하지 않는다(WD-364) — 실행은 사용자가 작업을 고친 것이 아니라 파생값을 남긴 것뿐이다({@link
   * #updateNextExecuteAt} 와 같은 원칙). 실행마다 수정 시각이 바뀌면 편집 화면을 연 사이 예약 실행·「지금 실행」이 한 번 돌았을 뿐인데, 아무도 작업을
   * 고치지 않았는데도 저장이 편집 충돌(409 EDIT_CONFLICT)로 거부된다. 목록의 "수정일"도 실행마다 흔들리지 않게 된다.
   */
  void updateLastExecuted(Long id, LocalDateTime lastExecutedAt, LocalDateTime nextExecuteAt);

  /**
   * 다음 실행 예정 시각만 갱신한다 (#348).
   *
   * <p>스케줄 등록/해제 시점에 호출된다. {@code updateLastExecuted} 와 달리 실행 이력을 건드리지 않으므로, 실행되지 않은 잡에도 값을 채울 수
   * 있다. 스케줄이 해제되면 {@code null} 을 넣어 "다음 실행 없음"을 표현한다.
   *
   * <p>{@code updated_at} 은 갱신하지 않는다 — 사용자가 잡을 수정한 것이 아니라 스케줄러가 파생값을 다시 계산한 것뿐이라, 여기서 갱신하면 목록의
   * "수정일"이 재부팅마다 흔들린다.
   */
  void updateNextExecuteAt(Long id, LocalDateTime nextExecuteAt);

  /**
   * 사용자 본인 작업을 지운다.
   *
   * @return 지운 행 수 — 동시 삭제에서 진 쪽은 0 이라 호출자가 404 로 바꾼다(WD-584)
   */
  int delete(Long id, Long userId);

  /**
   * 관리자 목록용 최소 행 — 작업 내용(프롬프트·설정)은 읽지 않는다.
   *
   * @param ownerId 작성자 id (자격 판정용, 응답에는 싣지 않는다)
   */
  record WorkspaceJobRow(
      Long id,
      String name,
      Long ownerId,
      String ownerName,
      Boolean enabled,
      LocalDateTime nextExecuteAt,
      String timezone) {}

  /** 이 워크스페이스의 스마트 작업 전부(최소 열 + 작성자 이름). 작성자 이름은 같은 질의에서 조인해 N+1 을 피한다. */
  List<WorkspaceJobRow> findWorkspaceRows(long tenantId);

  /** 이 워크스페이스의 작업 한 건(최소 행). 다른 워크스페이스·없는 작업이면 빈 값 — 호출자가 404 로 바꾼다. */
  Optional<WorkspaceJobRow> findWorkspaceRow(Long id, long tenantId);

  /** 관리자 끄기 — enabled 만 false 로 바꾼다. 켜기는 이 경로로 하지 않는다(자격 없는 사람이 자동 실행을 늘리면 안 된다). */
  void disableInTenant(Long id, long tenantId);

  /**
   * 관리자 삭제 — 작성자와 무관하게 이 워크스페이스의 작업을 지운다.
   *
   * @return 지운 행 수 — 동시 삭제에서 진 요청은 0 이라 호출자가 404 로 끝낸다(WD-584)
   */
  int deleteInTenant(Long id, long tenantId);
}
