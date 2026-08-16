package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.NOTIFICATION_OUTBOX;

import com.smartfirehub.notification.ChannelType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 작업 큐 jOOQ 구현. PG SKIP LOCKED + lease 컬럼으로 멀티 인스턴스 안전.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 이 왜 필요한가</b> —
 * {@link com.smartfirehub.global.tenant.TenantAwareTransactionManager} 의 "리포지토리에 클래스
 * 레벨 {@code @Transactional} 이 왜 필요한가" 문단 참조. 요약: GUC 는 트랜잭션이 열리는 순간에만
 * 심기고, 컨텍스트 공급은 여전히 호출자 책임이다.
 */
@Repository
@Transactional
class NotificationOutboxRepositoryImpl implements NotificationOutboxRepository {

  private final DSLContext dsl;

  NotificationOutboxRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insertIfAbsent(NotificationOutboxRow row) {
    dsl.insertInto(NOTIFICATION_OUTBOX)
        .set(NOTIFICATION_OUTBOX.IDEMPOTENCY_KEY, row.idempotencyKey())
        .set(NOTIFICATION_OUTBOX.CORRELATION_ID, row.correlationId())
        .set(NOTIFICATION_OUTBOX.EVENT_TYPE, row.eventType())
        .set(NOTIFICATION_OUTBOX.EVENT_SOURCE_ID, row.eventSourceId())
        .set(NOTIFICATION_OUTBOX.CHANNEL_TYPE, row.channelType().name())
        .set(NOTIFICATION_OUTBOX.RECIPIENT_USER_ID, row.recipientUserId())
        .set(NOTIFICATION_OUTBOX.RECIPIENT_ADDRESS, row.recipientAddress())
        .set(NOTIFICATION_OUTBOX.PAYLOAD_REF_TYPE, row.payloadRefType())
        .set(NOTIFICATION_OUTBOX.PAYLOAD_REF_ID, row.payloadRefId())
        .set(
            NOTIFICATION_OUTBOX.PAYLOAD,
            row.payloadJson() == null ? null : JSONB.valueOf(row.payloadJson()))
        .set(NOTIFICATION_OUTBOX.PAYLOAD_TYPE, row.payloadType())
        .onConflictOnConstraint(DSL.constraint("uk_outbox_idempotency"))
        .doNothing()
        .execute();
  }

  /**
   * 클래스 레벨 {@code @Transactional} 을 <b>명시적으로 거부</b>한다.
   *
   * <p>이 조회는 {@code SECURITY DEFINER} 함수라 RLS 를 우회하므로 GUC 가 필요 없다 — 그런데도
   * 클래스 레벨 애노테이션을 물려받으면 호출마다 BEGIN/COMMIT 2왕복이 덤으로 붙는다.
   * {@code onNotify} 경로는 enqueue 한 건마다 이 메서드를 부르므로 그 비용이 그대로 곱해진다.
   * {@code SlackWorkspaceTenantResolver} 가 같은 이유로 {@code @Transactional} 을 붙이지 않은 것과
   * 일관된다.
   *
   * <p>{@code NOT_SUPPORTED} 는 바깥 트랜잭션이 있으면 <b>중단(suspend)</b>시키므로 커밋된 상태만
   * 읽는다. 이 메서드의 호출자 4곳(메트릭 갱신·워커 폴링/NOTIFY·스위퍼·보존잡)은 전부 앰비언트
   * 트랜잭션이 없는 배경 스레드이고, NOTIFY 는 커밋 <b>후</b>에 도착하므로 문제가 되지 않는다.
   * 앞으로 트랜잭션 안에서 이 메서드를 부르려는 호출자가 생기면 그때 이 결정을 다시 봐야 한다.
   */
  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public List<Long> tenantIdsWithStatus(String... statuses) {
    // V106 의 SECURITY DEFINER 함수. 반환은 정수 목록뿐이라 노출면이 V95 수준으로 제한된다.
    // ::text[] 캐스트는 jOOQ 가 varchar[] 로 바인딩하기 때문에 필요하다.
    return dsl.fetch("select * from outbox_tenant_ids(?::text[])", (Object) statuses)
        .into(Long.class);
  }

  @Override
  public List<NotificationOutboxRow> claimDue(int batchSize, String instanceId, long tenantId) {
    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = cfg.dsl();
          List<Long> ids =
              tx.select(NOTIFICATION_OUTBOX.ID)
                  .from(NOTIFICATION_OUTBOX)
                  .where(NOTIFICATION_OUTBOX.STATUS.eq("PENDING"))
                  // 정책 이전에도 배치가 남의 테넌트로 새지 않도록 술어를 직접 건다(인터페이스 주석 참조).
                  .and(NOTIFICATION_OUTBOX.TENANT_ID.eq(tenantId))
                  .and(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT.le(OffsetDateTime.now()))
                  .orderBy(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT.asc())
                  .limit(batchSize)
                  .forUpdate()
                  .skipLocked()
                  .fetchInto(Long.class);

          if (ids.isEmpty()) return List.of();

          tx.update(NOTIFICATION_OUTBOX)
              .set(NOTIFICATION_OUTBOX.STATUS, "SENDING")
              .set(NOTIFICATION_OUTBOX.CLAIMED_AT, OffsetDateTime.now())
              .set(NOTIFICATION_OUTBOX.CLAIMED_BY, instanceId)
              .where(NOTIFICATION_OUTBOX.ID.in(ids))
              .execute();

          return tx.selectFrom(NOTIFICATION_OUTBOX)
              .where(NOTIFICATION_OUTBOX.ID.in(ids))
              .fetch(NotificationOutboxRepositoryImpl::toRow);
        });
  }

  @Override
  public void markSent(long id, String externalMessageId) {
    dsl.update(NOTIFICATION_OUTBOX)
        .set(NOTIFICATION_OUTBOX.STATUS, "SENT")
        .set(NOTIFICATION_OUTBOX.SENT_AT, OffsetDateTime.now())
        .set(NOTIFICATION_OUTBOX.LAST_ERROR, externalMessageId) // 외부 id 관측 기록용 (컬럼 재활용)
        .where(NOTIFICATION_OUTBOX.ID.eq(id))
        .execute();
  }

  @Override
  public void rescheduleTransient(
      long id, int newAttemptCount, Instant nextAttemptAt, String error) {
    dsl.update(NOTIFICATION_OUTBOX)
        .set(NOTIFICATION_OUTBOX.STATUS, "PENDING")
        .set(NOTIFICATION_OUTBOX.ATTEMPT_COUNT, newAttemptCount)
        .set(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT, nextAttemptAt.atOffset(ZoneOffset.UTC))
        .set(NOTIFICATION_OUTBOX.LAST_ERROR, error)
        .set(NOTIFICATION_OUTBOX.LAST_ERROR_AT, OffsetDateTime.now())
        .setNull(NOTIFICATION_OUTBOX.CLAIMED_AT)
        .setNull(NOTIFICATION_OUTBOX.CLAIMED_BY)
        .where(NOTIFICATION_OUTBOX.ID.eq(id))
        .execute();
  }

  @Override
  public void markPermanentFailure(long id, String reason, String error) {
    dsl.update(NOTIFICATION_OUTBOX)
        .set(NOTIFICATION_OUTBOX.STATUS, "PERMANENT_FAILURE")
        .set(NOTIFICATION_OUTBOX.PERMANENT_FAILURE_REASON, reason)
        .set(NOTIFICATION_OUTBOX.LAST_ERROR, error)
        .set(NOTIFICATION_OUTBOX.LAST_ERROR_AT, OffsetDateTime.now())
        .where(NOTIFICATION_OUTBOX.ID.eq(id))
        .execute();
  }

  @Override
  public int reclaimZombies(Instant cutoff) {
    return dsl.update(NOTIFICATION_OUTBOX)
        .set(NOTIFICATION_OUTBOX.STATUS, "PENDING")
        .setNull(NOTIFICATION_OUTBOX.CLAIMED_AT)
        .setNull(NOTIFICATION_OUTBOX.CLAIMED_BY)
        .where(NOTIFICATION_OUTBOX.STATUS.eq("SENDING"))
        .and(NOTIFICATION_OUTBOX.CLAIMED_AT.lt(cutoff.atOffset(ZoneOffset.UTC)))
        .execute();
  }

  @Override
  public List<NotificationOutboxRow> findByCorrelation(UUID correlationId) {
    return dsl.selectFrom(NOTIFICATION_OUTBOX)
        .where(NOTIFICATION_OUTBOX.CORRELATION_ID.eq(correlationId))
        .orderBy(NOTIFICATION_OUTBOX.CHANNEL_TYPE.asc())
        .fetch(NotificationOutboxRepositoryImpl::toRow);
  }

  @Override
  public Map<ChannelType, Long> countPendingByChannel(long tenantId) {
    // 채널마다 한 번씩 세면 트랜잭션도 그만큼 열린다(인터페이스 주석) — GROUP BY 로 한 번에 센다.
    Map<ChannelType, Long> counts = new EnumMap<>(ChannelType.class);
    dsl.select(NOTIFICATION_OUTBOX.CHANNEL_TYPE, DSL.count())
        .from(NOTIFICATION_OUTBOX)
        .where(NOTIFICATION_OUTBOX.STATUS.eq("PENDING"))
        .and(NOTIFICATION_OUTBOX.TENANT_ID.eq(tenantId))
        .groupBy(NOTIFICATION_OUTBOX.CHANNEL_TYPE)
        .fetch()
        .forEach(
            r -> {
              // 알 수 없는 채널 문자열(구버전 행·수기 삽입)은 건너뛴다 — 게이지 하나 때문에
              // 갱신 전체가 예외로 죽으면 다른 채널의 적체까지 보이지 않게 된다.
              try {
                counts.put(
                    ChannelType.valueOf(r.value1()), Long.valueOf(r.value2().longValue()));
              } catch (IllegalArgumentException ignored) {
                // 무시 — 아래 로그 대신 조용히 건너뛴다(리포지토리에 로거를 들이지 않는다).
              }
            });
    return counts;
  }

  @Override
  public List<NotificationOutboxRow> findStuckPending(Instant olderThan) {
    return dsl.selectFrom(NOTIFICATION_OUTBOX)
        .where(NOTIFICATION_OUTBOX.STATUS.eq("PENDING"))
        .and(NOTIFICATION_OUTBOX.CREATED_AT.lt(olderThan.atOffset(ZoneOffset.UTC)))
        .orderBy(NOTIFICATION_OUTBOX.CREATED_AT.asc())
        .limit(200)
        .fetch(NotificationOutboxRepositoryImpl::toRow);
  }

  @Override
  public void requeueForRetry(long id) {
    dsl.update(NOTIFICATION_OUTBOX)
        .set(NOTIFICATION_OUTBOX.STATUS, "PENDING")
        .set(NOTIFICATION_OUTBOX.ATTEMPT_COUNT, 0)
        .set(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT, OffsetDateTime.now(ZoneOffset.UTC))
        .setNull(NOTIFICATION_OUTBOX.CLAIMED_AT)
        .setNull(NOTIFICATION_OUTBOX.CLAIMED_BY)
        .setNull(NOTIFICATION_OUTBOX.LAST_ERROR)
        .setNull(NOTIFICATION_OUTBOX.LAST_ERROR_AT)
        .setNull(NOTIFICATION_OUTBOX.PERMANENT_FAILURE_REASON)
        .where(NOTIFICATION_OUTBOX.ID.eq(id))
        .execute();
  }

  @Override
  public int deleteSentOlderThan(Instant cutoff) {
    return dsl.deleteFrom(NOTIFICATION_OUTBOX)
        .where(NOTIFICATION_OUTBOX.STATUS.eq("SENT"))
        .and(NOTIFICATION_OUTBOX.SENT_AT.lt(cutoff.atOffset(ZoneOffset.UTC)))
        .execute();
  }

  @Override
  public int deletePermanentFailureOlderThan(Instant cutoff) {
    return dsl.deleteFrom(NOTIFICATION_OUTBOX)
        .where(NOTIFICATION_OUTBOX.STATUS.eq("PERMANENT_FAILURE"))
        .and(NOTIFICATION_OUTBOX.LAST_ERROR_AT.lt(cutoff.atOffset(ZoneOffset.UTC)))
        .execute();
  }

  private static NotificationOutboxRow toRow(Record r) {
    return new NotificationOutboxRow(
        r.get(NOTIFICATION_OUTBOX.ID),
        r.get(NOTIFICATION_OUTBOX.IDEMPOTENCY_KEY),
        r.get(NOTIFICATION_OUTBOX.CORRELATION_ID),
        r.get(NOTIFICATION_OUTBOX.EVENT_TYPE),
        r.get(NOTIFICATION_OUTBOX.EVENT_SOURCE_ID),
        ChannelType.valueOf(r.get(NOTIFICATION_OUTBOX.CHANNEL_TYPE)),
        r.get(NOTIFICATION_OUTBOX.RECIPIENT_USER_ID),
        r.get(NOTIFICATION_OUTBOX.RECIPIENT_ADDRESS),
        r.get(NOTIFICATION_OUTBOX.PAYLOAD_REF_TYPE),
        r.get(NOTIFICATION_OUTBOX.PAYLOAD_REF_ID),
        r.get(NOTIFICATION_OUTBOX.PAYLOAD) == null
            ? null
            : r.get(NOTIFICATION_OUTBOX.PAYLOAD).data(),
        r.get(NOTIFICATION_OUTBOX.PAYLOAD_TYPE),
        r.get(NOTIFICATION_OUTBOX.STATUS),
        r.get(NOTIFICATION_OUTBOX.ATTEMPT_COUNT),
        r.get(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT).toInstant());
  }
}
