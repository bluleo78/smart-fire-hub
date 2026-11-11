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

  /**
   * 주어진 상태의 행을 가진 테넌트 id 목록 — 정책을 넘는 함수(PG SECURITY DEFINER {@code outbox_tenant_ids}, Tibero
   * {@code FH_TENANT.outbox_tenant_ids} 커서)를 부른다. PG 는 반환 목록으로, Tibero 는 커서 OUT 인자로 {@link
   * TenantIdsQuery#getTenantIds()} 에 돌려준다(이때 반환 목록은 비어 있다).
   */
  List<Long> tenantIdsWithStatus(@Param("query") TenantIdsQuery query);

  /**
   * {@link #tenantIdsWithStatus} 의 입출력 — 벤더마다 결과가 오는 자리가 달라(PG 결과 집합, Tibero 커서 OUT 인자) 한 매퍼 시그니처로
   * 두려고 가변 객체로 둔다. MyBatis 가 Tibero 커서 결과를 {@code tenantIds} 세터로 넣는다.
   */
  final class TenantIdsQuery {
    private final List<String> statuses;
    private List<Long> tenantIds;

    public TenantIdsQuery(List<String> statuses) {
      this.statuses = List.copyOf(statuses);
    }

    /** PG 문장이 배열로 펼치는 상태 목록. */
    public List<String> getStatuses() {
      return statuses;
    }

    /** Tibero 함수 인자 — 쉼표 구분 상태 목록(컬렉션 타입을 만들 권한이 없어 문자열로 받는다, V156.1). */
    public String getStatusesCsv() {
      return String.join(",", statuses);
    }

    /** Tibero 커서 OUT 결과(PG 는 null). */
    public List<Long> getTenantIds() {
      return tenantIds;
    }

    public void setTenantIds(List<Long> tenantIds) {
      this.tenantIds = tenantIds;
    }
  }

  /**
   * 처리 대상 id 를 잠그며 고른다(FOR UPDATE SKIP LOCKED). 행 제한과 {@code rowBounds} 의 관계는 XML Tibero 판 주석 참조.
   */
  List<Long> selectDueIdsForUpdate(
      @Param("tenantId") Long tenantId,
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
      @Param("id") Long id,
      @Param("sentAt") OffsetDateTime sentAt,
      @Param("externalMessageId") String externalMessageId);

  int rescheduleTransient(
      @Param("id") Long id,
      @Param("attemptCount") int attemptCount,
      @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
      @Param("error") String error,
      @Param("errorAt") OffsetDateTime errorAt);

  int markPermanentFailure(
      @Param("id") Long id,
      @Param("reason") String reason,
      @Param("error") String error,
      @Param("errorAt") OffsetDateTime errorAt);

  int reclaimZombies(@Param("cutoff") OffsetDateTime cutoff);

  List<NotificationOutboxRow> findByCorrelation(@Param("correlationId") UUID correlationId);

  /** 채널별 PENDING 건수. 키 channel_type, cnt. */
  List<Map<String, Object>> countPendingByChannel(@Param("tenantId") Long tenantId);

  List<NotificationOutboxRow> findStuckPending(@Param("olderThan") OffsetDateTime olderThan);

  int requeueForRetry(@Param("id") Long id, @Param("now") OffsetDateTime now);

  int deleteSentOlderThan(@Param("cutoff") OffsetDateTime cutoff);

  int deletePermanentFailureOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
