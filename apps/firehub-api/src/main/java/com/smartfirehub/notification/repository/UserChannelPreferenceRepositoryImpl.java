package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.USER_CHANNEL_PREFERENCE;

import com.smartfirehub.notification.ChannelType;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * jOOQ 기반 사용자 채널 preference 구현.
 *
 * <p>isEnabled 정책: 테이블에 row가 없으면 기본값 true로 간주 (opt-in 없이도 정상 발송). CHAT 채널은 DB CHECK 제약으로 disable
 * 불가능하므로 항상 true 반환.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 이 왜 필요한가</b> —
 * {@link com.smartfirehub.global.tenant.TenantAwareTransactionManager} 의 "리포지토리에 클래스
 * 레벨 {@code @Transactional} 이 왜 필요한가" 문단 참조. 요약: GUC 는 트랜잭션이 열리는 순간에만
 * 심기고, 컨텍스트 공급은 여전히 호출자 책임이다.
 */
@Repository
@Transactional
class UserChannelPreferenceRepositoryImpl implements UserChannelPreferenceRepository {

  private final DSLContext dsl;

  UserChannelPreferenceRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public boolean isEnabled(long userId, ChannelType channelType) {
    // CHAT은 안전망 — DB 조회 생략, 항상 true
    if (channelType == ChannelType.CHAT) return true;

    Boolean enabled =
        dsl.select(USER_CHANNEL_PREFERENCE.ENABLED)
            .from(USER_CHANNEL_PREFERENCE)
            .where(USER_CHANNEL_PREFERENCE.USER_ID.eq(userId))
            .and(USER_CHANNEL_PREFERENCE.CHANNEL_TYPE.eq(channelType.name()))
            .fetchOne(USER_CHANNEL_PREFERENCE.ENABLED);

    // row 없으면 기본값 true (사용자가 명시적 off를 한 적이 없으면 허용)
    return enabled == null || enabled;
  }

  @Override
  public void setEnabled(long userId, ChannelType channelType, boolean enabled) {
    // ON CONFLICT (tenant_id, user_id, channel_type) DO UPDATE — upsert 방식으로 idempotent 처리.
    // tenant_id 는 GUC DEFAULT 로 채워지므로 여기서 set 하지 않지만, V106 이 uk_preference 를
    // (tenant_id, user_id, channel_type) 으로 접었으므로 충돌 컬럼 목록에는 반드시 있어야 한다.
    // 빠뜨리면 PostgreSQL 이 42P10(matching constraint 없음)으로 파싱 단계에서 거부한다.
    dsl.insertInto(USER_CHANNEL_PREFERENCE)
        .set(USER_CHANNEL_PREFERENCE.USER_ID, userId)
        .set(USER_CHANNEL_PREFERENCE.CHANNEL_TYPE, channelType.name())
        .set(USER_CHANNEL_PREFERENCE.ENABLED, enabled)
        .onConflict(
            USER_CHANNEL_PREFERENCE.TENANT_ID,
            USER_CHANNEL_PREFERENCE.USER_ID,
            USER_CHANNEL_PREFERENCE.CHANNEL_TYPE)
        .doUpdate()
        .set(USER_CHANNEL_PREFERENCE.ENABLED, enabled)
        .execute();
  }
}
