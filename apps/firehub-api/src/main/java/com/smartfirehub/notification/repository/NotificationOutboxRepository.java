package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Outbox 작업 큐 접근 인터페이스. 워커·디스패처가 사용. */
public interface NotificationOutboxRepository {

  /** AFTER_COMMIT 훅에서 호출. idempotency_key UNIQUE 충돌은 ON CONFLICT DO NOTHING. */
  void insertIfAbsent(NotificationOutboxRow row);

  /**
   * 배경 경로용 — 주어진 상태의 행을 가진 테넌트 id 목록(중복 제거).
   *
   * <p><b>무엇을.</b> V106 의 {@code outbox_tenant_ids(TEXT[])}({@code SECURITY DEFINER},
   * {@code STABLE}) 를 호출한다. 상태로 용도를 가른다 — 클레임 {@code PENDING}, 좀비 리퍼
   * {@code SENDING}, 보존 삭제 {@code SENT, PERMANENT_FAILURE}, 메트릭 게이지 {@code PENDING}.
   *
   * <p><b>왜 definer 인가.</b> 스케줄러·메트릭 스레드에는 테넌트 컨텍스트가 없어 RLS(V107)가 켜지면
   * 전 행이 차단된다. 그렇다고 이 함수에 클레임까지 시키면 안 된다:
   * {@code AnalyticsQueryExecutionService.executeDirectly} 가 사용자 SQL 을 런타임 롤
   * ({@code app_tenant})로 그대로 실행하므로, 쓰기 definer 에 EXECUTE 를 주는 순간 쿼리 UI 사용자
   * 아무나가 전 테넌트 outbox 행을 뒤집을 수 있다(계획 R3). 그래서 <b>읽기 전용 정수 목록</b>까지만
   * 노출하고, 실제 클레임·회수·삭제는 호출자가 연 테넌트 컨텍스트 안에서 한다.
   *
   * <p><b>왜 {@code TenantScopedRunner.forEachActiveTenant} 가 아닌가.</b> 그쪽은 ACTIVE 테넌트만
   * 돌아, 비활성·정지 테넌트의 행이 영원히 좀비 회수도 보존 삭제도 되지 않는 누수가 생긴다(R4).
   * 이 함수는 테이블에 실제로 존재하는 테넌트에서 유도하므로 그 구멍이 없다. <b>순회 자체는
   * 여전히 러너가 한다</b> — 호출자는 이 목록을 {@code TenantScopedRunner.forEachTenant} 에 넘긴다.
   * R4 가 배제한 것은 출처이지 루프가 아니다.
   *
   * <p>반환은 <b>완전히 구체화된 리스트</b>다(스트림 아님). GUC 는 트랜잭션이 열리는 순간에만
   * 주입되므로, 이 조회의 트랜잭션이 순회 전체를 덮으면 첫 테넌트의 GUC 가 그대로 고정된다.
   */
  List<Long> tenantIdsWithStatus(String... statuses);

  /**
   * 워커: PENDING + next_attempt_at<=now() 인 행 N개를 claim. SELECT FOR UPDATE SKIP LOCKED → UPDATE
   * status=SENDING, claimed_at=now, claimed_by=instance. 단일 트랜잭션 안에서 처리.
   *
   * <p><b>{@code tenantId} 를 명시적으로 받는 이유.</b> 정책(V107)이 켜지기 전까지 이 쿼리는
   * 전역이라, 워커가 테넌트 A 의 컨텍스트에서 B 의 행을 클레임해 <b>A 로 배달</b>하는 창이 생긴다.
   * 컨텍스트만으로는 그 창이 닫히지 않으므로 술어를 직접 건다. 정책 이후에는 정책이 붙일 술어와
   * 같아 중복이지만, V108 의 {@code idx_outbox_pending_tenant_due} 가 애초에 tenant_id 를 선행
   * 컬럼으로 갖고 있어 이 술어가 바로 Index Cond 로 접힌다 — 계획을 바꾸지 않으므로 비용이 없다.
   * (배달 결과가 테넌트에 귀속되지 않는 전역 집합 연산 — 좀비 회수·보존 삭제 — 에는 같은 술어를
   * 걸지 않는다. 그쪽은 멱등이고 정책이 켜지면 자동으로 좁혀진다.)
   */
  List<NotificationOutboxRow> claimDue(int batchSize, String instanceId, long tenantId);

  /** 발송 성공 시 상태 기록. externalMessageId는 관측 용도(last_error 컬럼에 함께 기록). */
  void markSent(long id, String externalMessageId);

  /** 일시 실패 시 backoff 재스케줄. status=PENDING으로 되돌리고 claim 컬럼 clear. */
  void rescheduleTransient(long id, int newAttemptCount, Instant nextAttemptAt, String error);

  /** 영구 실패 기록. 후속 재시도 없음. */
  void markPermanentFailure(long id, String reason, String error);

  /** 좀비 회복: SENDING이고 claimed_at < cutoff인 행을 PENDING으로 되돌림. 반환값=회복된 행 수. */
  int reclaimZombies(Instant cutoff);

  /** correlation 묶음 조회 (관측·UI). */
  List<NotificationOutboxRow> findByCorrelation(UUID correlationId);

  /**
   * 관측 — 한 테넌트의 <b>채널별</b> PENDING 행 개수를 한 번에 (Micrometer gauge).
   *
   * <p>게이지가 테넌트 태그를 달게 됐으므로 값도 테넌트별이어야 한다. 정책 이전에는 컨텍스트만으로
   * 좁혀지지 않아 모든 테넌트가 같은 전역 수치를 보고하게 되므로 술어를 명시한다({@link
   * #claimDue} 와 같은 이유).
   *
   * <p><b>왜 채널당 한 번이 아니라 {@code GROUP BY} 한 번인가.</b> 이 리포지토리는 클래스 레벨
   * {@code @Transactional} 이고 호출자({@code NotificationMetrics})는 {@code runScoped} 로
   * 컨텍스트만 세울 뿐 트랜잭션을 열지 않는다 — 즉 <b>호출 한 번마다 자기 트랜잭션</b>이라
   * BEGIN + {@code set_config} + SELECT + COMMIT 왕복 4회가 채널 수만큼 곱해졌다. 한 쿼리로 접으면
   * 테넌트당 4왕복으로 고정된다.
   *
   * <p>반환 맵에는 <b>PENDING 행이 있는 채널만</b> 들어온다. 없는 채널은 호출자의 스왑 로직이
   * 이미 0 으로 내리므로(드레인 처리) 의미가 같다.
   */
  Map<ChannelType, Long> countPendingByChannel(long tenantId);

  /** 관측 — olderThan 보다 오래된 PENDING 행 목록 (admin stuck 조회). 최대 200건. */
  List<NotificationOutboxRow> findStuckPending(Instant olderThan);

  /** 관리자 수동 재투입. PENDING으로 되돌리고 attempt_count=0, next_attempt_at=now. */
  void requeueForRetry(long id);

  /** SENT 행 중 cutoff 이전을 삭제. 반환=삭제 행 수. */
  int deleteSentOlderThan(Instant cutoff);

  /** PERMANENT_FAILURE 행 중 cutoff 이전을 삭제. 반환=삭제 행 수. */
  int deletePermanentFailureOlderThan(Instant cutoff);

  /** outbox 한 행의 관측·검증용 스냅샷. */
  record NotificationOutboxRow(
      Long id,
      String idempotencyKey,
      UUID correlationId,
      String eventType,
      Long eventSourceId,
      ChannelType channelType,
      Long recipientUserId,
      String recipientAddress,
      String payloadRefType,
      Long payloadRefId,
      String payloadJson,
      String payloadType,
      String status,
      int attemptCount,
      Instant nextAttemptAt) {}
}
