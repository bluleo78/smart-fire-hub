package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.RowBounds;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * NotificationOutboxRepository 의 MyBatis 구현(WD-487 PoC) — {@code JooqNotificationOutboxRepository}
 * 와 같은 의미.
 *
 * <p>{@code @Repository} 없이 {@code MyBatisPlatformConfig} 가 {@code autowireCandidate=false} 빈으로
 * 등록한다.
 */
// 클래스 레벨 @Transactional 이유: RLS 테넌트 GUC 는 트랜잭션이 열리는 순간에만 심기고, 컨텍스트 공급은
// 호출자 책임이다(TenantAwareTransactionManager 참조). 열지 않으면 tenant_id 기본값이 비어 INSERT 가
// 실패하고 조회는 0행이 된다. 지우지 말 것.
@Transactional
public class MyBatisNotificationOutboxRepository implements NotificationOutboxRepository {

  private final NotificationOutboxMapper mapper;

  public MyBatisNotificationOutboxRepository(NotificationOutboxMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void insertIfAbsent(NotificationOutboxRow row) {
    mapper.insertIfAbsent(
        row.idempotencyKey(),
        row.correlationId(),
        row.eventType(),
        row.eventSourceId(),
        row.channelType().name(),
        row.recipientUserId(),
        row.recipientAddress(),
        row.payloadRefType(),
        row.payloadRefId(),
        row.payloadJson(),
        row.payloadType());
  }

  /**
   * 클래스 레벨 트랜잭션을 거부한다 — SECURITY DEFINER 함수라 GUC 가 필요 없고, 호출마다 BEGIN/COMMIT 왕복이 붙는 것을 피한다. jOOQ 구현과
   * 같은 이유·같은 전제(호출자는 전부 앰비언트 트랜잭션 없는 배경 스레드)다.
   */
  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public List<Long> tenantIdsWithStatus(String... statuses) {
    // 상태가 없으면 DB 에 가지 않고 빈 목록 — jOOQ 구현(status = ANY(빈 배열))과 같은 결과다. Tibero 문장은 IN (…) 이라 빈 목록이면
    // "IN ()" 구문 오류가 나므로 두 벤더 모두 여기서 막는다.
    if (statuses == null || statuses.length == 0) return List.of();
    return mapper.tenantIdsWithStatus(Arrays.asList(statuses));
  }

  /**
   * select-for-update → update → select 를 한 트랜잭션에서 수행한다. jOOQ 구현은 {@code transactionResult}(바깥
   * 트랜잭션이 있으면 savepoint 중첩)지만 여기선 클래스 레벨 {@code @Transactional}(REQUIRED)이라 바깥 트랜잭션에 합류한다.
   */
  @Override
  public List<NotificationOutboxRow> claimDue(int batchSize, String instanceId, long tenantId) {
    // 시각은 jOOQ 와 같이 자바에서 바인딩한다. RowBounds 는 Tibero 판의 유일한 행 제한이다(XML selectDueIdsForUpdate 참조).
    List<Long> ids =
        mapper.selectDueIdsForUpdate(
            tenantId, OffsetDateTime.now(ZoneOffset.UTC), batchSize, new RowBounds(0, batchSize));
    if (ids.isEmpty()) return List.of();
    mapper.markSending(ids, OffsetDateTime.now(ZoneOffset.UTC), instanceId);
    return mapper.findByIds(ids);
  }

  @Override
  public void markSent(long id, String externalMessageId) {
    // 외부 id 는 관측 기록용으로 last_error 컬럼을 재활용한다(jOOQ 구현과 같음).
    mapper.markSent(id, OffsetDateTime.now(ZoneOffset.UTC), externalMessageId);
  }

  @Override
  public void rescheduleTransient(
      long id, int newAttemptCount, Instant nextAttemptAt, String error) {
    mapper.rescheduleTransient(
        id,
        newAttemptCount,
        nextAttemptAt.atOffset(ZoneOffset.UTC),
        error,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  @Override
  public void markPermanentFailure(long id, String reason, String error) {
    mapper.markPermanentFailure(id, reason, error, OffsetDateTime.now(ZoneOffset.UTC));
  }

  @Override
  public int reclaimZombies(Instant cutoff) {
    return mapper.reclaimZombies(cutoff.atOffset(ZoneOffset.UTC));
  }

  @Override
  public List<NotificationOutboxRow> findByCorrelation(UUID correlationId) {
    return mapper.findByCorrelation(correlationId);
  }

  @Override
  public Map<ChannelType, Long> countPendingByChannel(long tenantId) {
    Map<ChannelType, Long> counts = new EnumMap<>(ChannelType.class);
    for (Map<String, Object> r : mapper.countPendingByChannel(tenantId)) {
      // 알 수 없는 채널 문자열(구버전 행·수기 삽입)은 건너뛴다 — 게이지 하나 때문에 전체 갱신이 죽지 않게.
      try {
        counts.put(
            ChannelType.valueOf((String) r.get("channel_type")),
            ((Number) r.get("cnt")).longValue());
      } catch (IllegalArgumentException ignored) {
        // 무시
      }
    }
    return counts;
  }

  @Override
  public List<NotificationOutboxRow> findStuckPending(Instant olderThan) {
    return mapper.findStuckPending(olderThan.atOffset(ZoneOffset.UTC));
  }

  @Override
  public int requeueForRetry(long id) {
    return mapper.requeueForRetry(id, OffsetDateTime.now(ZoneOffset.UTC));
  }

  @Override
  public int deleteSentOlderThan(Instant cutoff) {
    return mapper.deleteSentOlderThan(cutoff.atOffset(ZoneOffset.UTC));
  }

  @Override
  public int deletePermanentFailureOlderThan(Instant cutoff) {
    return mapper.deletePermanentFailureOlderThan(cutoff.atOffset(ZoneOffset.UTC));
  }
}
