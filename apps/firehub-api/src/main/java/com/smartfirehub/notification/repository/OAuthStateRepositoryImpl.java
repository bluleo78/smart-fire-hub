package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.OAUTH_STATE;

import com.smartfirehub.notification.ChannelType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** OAuth CSRF state 저장·단일소비 jOOQ 구현. FOR UPDATE + consumed_at 마킹으로 재사용 방지. */
@Repository
class OAuthStateRepositoryImpl implements OAuthStateRepository {

  private final DSLContext dsl;

  OAuthStateRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void create(String state, long userId, ChannelType channelType, Instant expiresAt) {
    dsl.insertInto(OAUTH_STATE)
        .set(OAUTH_STATE.STATE, state)
        .set(OAUTH_STATE.USER_ID, userId)
        .set(OAUTH_STATE.CHANNEL_TYPE, channelType.name())
        .set(OAUTH_STATE.EXPIRES_AT, expiresAt.atOffset(ZoneOffset.UTC))
        .execute();
  }

  @Override
  public Optional<ConsumedState> consume(String state) {
    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = cfg.dsl();
          Record row =
              tx.select(OAUTH_STATE.USER_ID, OAUTH_STATE.CHANNEL_TYPE)
                  .from(OAUTH_STATE)
                  .where(OAUTH_STATE.STATE.eq(state))
                  .and(OAUTH_STATE.CONSUMED_AT.isNull())
                  .and(OAUTH_STATE.EXPIRES_AT.gt(OffsetDateTime.now()))
                  .forUpdate()
                  .fetchOne();
          if (row == null) return Optional.empty();

          tx.update(OAUTH_STATE)
              .set(OAUTH_STATE.CONSUMED_AT, OffsetDateTime.now())
              .where(OAUTH_STATE.STATE.eq(state))
              .execute();

          return Optional.of(
              new ConsumedState(
                  row.get(OAUTH_STATE.USER_ID),
                  ChannelType.valueOf(row.get(OAUTH_STATE.CHANNEL_TYPE))));
        });
  }

  @Override
  public int deleteExpired() {
    return dsl.deleteFrom(OAUTH_STATE)
        .where(OAUTH_STATE.EXPIRES_AT.lt(OffsetDateTime.now()))
        .execute();
  }
}
