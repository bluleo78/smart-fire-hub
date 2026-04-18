package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.NOTIFICATION_OUTBOX;

import com.smartfirehub.notification.ChannelType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Outbox 작업 큐 jOOQ 구현. PG SKIP LOCKED + lease 컬럼으로 멀티 인스턴스 안전. */
@Repository
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
                .set(NOTIFICATION_OUTBOX.PAYLOAD, row.payloadJson() == null ? null : JSONB.valueOf(row.payloadJson()))
                .set(NOTIFICATION_OUTBOX.PAYLOAD_TYPE, row.payloadType())
                .onConflictOnConstraint(DSL.constraint("uk_outbox_idempotency"))
                .doNothing()
                .execute();
    }

    @Override
    public List<NotificationOutboxRow> claimDue(int batchSize, String instanceId) {
        return dsl.transactionResult(cfg -> {
            DSLContext tx = cfg.dsl();
            List<Long> ids = tx.select(NOTIFICATION_OUTBOX.ID)
                    .from(NOTIFICATION_OUTBOX)
                    .where(NOTIFICATION_OUTBOX.STATUS.eq("PENDING"))
                    .and(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT.le(OffsetDateTime.now()))
                    .orderBy(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT.asc())
                    .limit(batchSize)
                    .forUpdate().skipLocked()
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
                .set(NOTIFICATION_OUTBOX.LAST_ERROR, externalMessageId)   // 외부 id 관측 기록용 (컬럼 재활용)
                .where(NOTIFICATION_OUTBOX.ID.eq(id))
                .execute();
    }

    @Override
    public void rescheduleTransient(long id, int newAttemptCount, Instant nextAttemptAt, String error) {
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
    public long countPending(ChannelType channelType) {
        Integer count = dsl.selectCount()
                .from(NOTIFICATION_OUTBOX)
                .where(NOTIFICATION_OUTBOX.STATUS.eq("PENDING"))
                .and(NOTIFICATION_OUTBOX.CHANNEL_TYPE.eq(channelType.name()))
                .fetchOne(0, Integer.class);
        return count == null ? 0L : count.longValue();
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
                r.get(NOTIFICATION_OUTBOX.PAYLOAD) == null ? null : r.get(NOTIFICATION_OUTBOX.PAYLOAD).data(),
                r.get(NOTIFICATION_OUTBOX.PAYLOAD_TYPE),
                r.get(NOTIFICATION_OUTBOX.STATUS),
                r.get(NOTIFICATION_OUTBOX.ATTEMPT_COUNT),
                r.get(NOTIFICATION_OUTBOX.NEXT_ATTEMPT_AT).toInstant()
        );
    }
}
