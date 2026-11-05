package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.RowBounds;

/**
 * notification_outbox MyBatis 매퍼(WD-487 PoC). SQL 은 {@code
 * mybatis/notification/NotificationOutboxMapper.xml}.
 *
 * <p>문장 1:1 바인딩만 한다. 시각은 jOOQ 구현과 같이 자바에서 {@link OffsetDateTime}(UTC)으로 만들어 넘긴다 — SQL now() 로 바꾸지
 * 않는다.
 */
@PlatformMapper
public interface NotificationOutboxMapper {

  /** 멱등 키 중복이면 아무것도 하지 않는 INSERT. tenant_id 는 컬럼 DEFAULT 가 채운다. */
  int insertIfAbsent(
      @Param("idempotencyKey") String idempotencyKey,
      @Param("correlationId") UUID correlationId,
      @Param("eventType") String eventType,
      @Param("eventSourceId") Long eventSourceId,
      @Param("channelType") String channelType,
      @Param("recipientUserId") Long recipientUserId,
      @Param("recipientAddress") String recipientAddress,
      @Param("payloadRefType") String payloadRefType,
      @Param("payloadRefId") Long payloadRefId,
      @Param("payloadJson") String payloadJson,
      @Param("payloadType") String payloadType);

  /** 주어진 상태의 행을 가진 테넌트 id 목록(SECURITY DEFINER 함수). */
  List<Long> tenantIdsWithStatus(@Param("statuses") List<String> statuses);

  /**
   * 처리 대상 id 를 잠그며 고른다(FOR UPDATE SKIP LOCKED). 행 제한과 {@code rowBounds} 의 관계는 XML Tibero 판 주석 참조.
   */
  List<Long> selectDueIdsForUpdate(
      @Param("tenantId") long tenantId,
      @Param("now") OffsetDateTime now,
      @Param("batchSize") int batchSize,
      RowBounds rowBounds);

  /** 고른 행을 SENDING 으로 바꾼다. */
  int markSending(
      @Param("ids") List<Long> ids,
      @Param("claimedAt") OffsetDateTime claimedAt,
      @Param("instanceId") String instanceId);

  /** id 목록의 행 조회(클레임 직후 읽기). 로컬 캐시 무효화 포함. */
  List<NotificationOutboxRow> findByIds(@Param("ids") List<Long> ids);

  int markSent(
      @Param("id") long id,
      @Param("sentAt") OffsetDateTime sentAt,
      @Param("externalMessageId") String externalMessageId);

  int rescheduleTransient(
      @Param("id") long id,
      @Param("attemptCount") int attemptCount,
      @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
      @Param("error") String error,
      @Param("errorAt") OffsetDateTime errorAt);

  int markPermanentFailure(
      @Param("id") long id,
      @Param("reason") String reason,
      @Param("error") String error,
      @Param("errorAt") OffsetDateTime errorAt);

  int reclaimZombies(@Param("cutoff") OffsetDateTime cutoff);

  List<NotificationOutboxRow> findByCorrelation(@Param("correlationId") UUID correlationId);

  /** 채널별 PENDING 건수. 키 channel_type, cnt. */
  List<Map<String, Object>> countPendingByChannel(@Param("tenantId") long tenantId);

  List<NotificationOutboxRow> findStuckPending(@Param("olderThan") OffsetDateTime olderThan);

  int requeueForRetry(@Param("id") long id, @Param("now") OffsetDateTime now);

  int deleteSentOlderThan(@Param("cutoff") OffsetDateTime cutoff);

  int deletePermanentFailureOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
