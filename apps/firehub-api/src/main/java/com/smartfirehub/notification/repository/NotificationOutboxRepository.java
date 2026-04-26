package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outbox 작업 큐 접근 인터페이스. 워커·디스패처가 사용. */
public interface NotificationOutboxRepository {

  /** AFTER_COMMIT 훅에서 호출. idempotency_key UNIQUE 충돌은 ON CONFLICT DO NOTHING. */
  void insertIfAbsent(NotificationOutboxRow row);

  /**
   * 워커: PENDING + next_attempt_at<=now() 인 행 N개를 claim. SELECT FOR UPDATE SKIP LOCKED → UPDATE
   * status=SENDING, claimed_at=now, claimed_by=instance. 단일 트랜잭션 안에서 처리.
   */
  List<NotificationOutboxRow> claimDue(int batchSize, String instanceId);

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

  /** 관측 — 채널별 PENDING 행 개수 (Micrometer gauge). */
  long countPending(ChannelType channelType);

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
