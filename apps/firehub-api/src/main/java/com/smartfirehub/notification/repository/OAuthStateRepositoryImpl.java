package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.OAUTH_STATE;

import com.smartfirehub.notification.ChannelType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth CSRF state 저장·단일소비 jOOQ 구현. FOR UPDATE + consumed_at 마킹으로 재사용 방지.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 이 왜 필요한가</b> —
 * {@link com.smartfirehub.global.tenant.TenantAwareTransactionManager} 의 "리포지토리에 클래스
 * 레벨 {@code @Transactional} 이 왜 필요한가" 문단 참조.
 *
 * <p><b>이 지점만의 사실 두 가지.</b> 첫째, 컨텍스트를 공급하는 호출자가 인증된 HTTP 경로(JWT
 * 필터가 세움)와 보존 삭제 크론(P2-f Task 3 이 배선) 둘이다. 둘째, 다른 4개 채널 리포지토리와
 * 달리 <b>읽기 쪽 위험은 없다</b> — {@code oauth_state} 는 V106 [R7] 대로 컬럼만 있고 RLS 정책이
 * 없어서 GUC 가 비어도 조용히 0행이 되지는 않는다. 즉 <b>여기서 이 애노테이션이 막는 것은
 * {@code oauth_state.tenant_id} GUC DEFAULT 가 NULL 이 되어 INSERT 가 터지는 23502 하나뿐</b>이다.
 */
@Repository
@Transactional
class OAuthStateRepositoryImpl implements OAuthStateRepository {

  private static final Logger log = LoggerFactory.getLogger(OAuthStateRepositoryImpl.class);

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
          // TENANT_ID 를 함께 읽는다 — permitAll 콜백이 테넌트를 되찾는 유일한 수단이다(V106 [R7]).
          // 이 SELECT 가 컨텍스트 없이도 행을 보는 것은 oauth_state 에 RLS 정책이 없기 때문이다.
          // 정책을 걸면 콜백이 영원히 0행이 되어 Slack 설치·Kakao 연동이 통째로 죽는다.
          Record row =
              tx.select(OAUTH_STATE.USER_ID, OAUTH_STATE.CHANNEL_TYPE, OAUTH_STATE.TENANT_ID)
                  .from(OAUTH_STATE)
                  .where(OAUTH_STATE.STATE.eq(state))
                  .and(OAUTH_STATE.CONSUMED_AT.isNull())
                  .and(OAUTH_STATE.EXPIRES_AT.gt(OffsetDateTime.now()))
                  .forUpdate()
                  .fetchOne();
          if (row == null) return Optional.empty();

          // fail-closed: 테넌트를 실어 오지 못한 state 는 소비하지 않고 무효로 취급한다.
          // 컬럼이 NOT NULL 이라 정상 경로에서는 도달할 수 없지만, 조용히 기본 테넌트로
          // 떨어지는 것이 최악이므로 방어를 남긴다. 호출부(콜백)는 empty 를 이미 400 으로 처리한다.
          Long tenantId = row.get(OAUTH_STATE.TENANT_ID);
          if (tenantId == null) {
            log.error("oauth_state 에 tenant_id 가 없다 — 콜백을 진행할 수 없다 (state 앞 8자={})", prefix(state));
            return Optional.empty();
          }

          tx.update(OAUTH_STATE)
              .set(OAUTH_STATE.CONSUMED_AT, OffsetDateTime.now())
              .where(OAUTH_STATE.STATE.eq(state))
              .execute();

          return Optional.of(
              new ConsumedState(
                  row.get(OAUTH_STATE.USER_ID),
                  ChannelType.valueOf(row.get(OAUTH_STATE.CHANNEL_TYPE)),
                  tenantId));
        });
  }

  /** state 는 CSRF 비밀값이므로 로그에 전문을 남기지 않는다. 추적에 필요한 앞부분만 남긴다. */
  private static String prefix(String state) {
    if (state == null) return "null";
    return state.length() <= 8 ? state : state.substring(0, 8);
  }

  @Override
  public int deleteExpired() {
    return dsl.deleteFrom(OAUTH_STATE)
        .where(OAUTH_STATE.EXPIRES_AT.lt(OffsetDateTime.now()))
        .execute();
  }
}
