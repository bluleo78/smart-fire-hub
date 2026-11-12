package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.OAuthStateRepository;
import com.smartfirehub.notification.repository.mybatis.OAuthStateMapper.StateRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuthStateRepository 의 MyBatis 구현(WD-709) — {@code JooqOAuthStateRepository} 와 같은 의미.
 *
 * <p>클래스 단위 {@code @Transactional} 은 jOOQ 구현 그대로다. 지우면 안 되는 이유: oauth_state 는 RLS 정책이 없어 읽기는 안전하지만,
 * INSERT 의 tenant_id 열 DEFAULT(테넌트 GUC)는 트랜잭션이 열릴 때만 심겨 열지 않으면 NULL 로 INSERT 가 실패한다. consume 의 FOR
 * UPDATE 도 호출자 트랜잭션 안에서만 의미가 있다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisOAuthStateRepository implements OAuthStateRepository {

  private static final Logger log = LoggerFactory.getLogger(MyBatisOAuthStateRepository.class);

  private final OAuthStateMapper mapper;

  public MyBatisOAuthStateRepository(OAuthStateMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void create(String state, long userId, ChannelType channelType, Instant expiresAt) {
    mapper.insert(state, userId, channelType.name(), expiresAt.atOffset(ZoneOffset.UTC));
  }

  /**
   * 미소비·미만료 state 를 잠그고 읽어 consumed_at 을 찍는다. 두 트랜잭션이 같은 state 를 동시에 소비하면 뒤쪽은 행 잠금에서 기다렸다가 empty 를
   * 받는다 — 조건을 다시 평가하지 않는 DB 에 대비해 markConsumed 가 {@code consumed_at IS NULL} 을 다시 걸고 갱신 행 수가 0 이면
   * empty 로 돌린다.
   */
  @Override
  public Optional<ConsumedState> consume(String state) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    StateRow row = mapper.selectConsumableForUpdate(state, now);
    if (row == null) {
      return Optional.empty();
    }
    // fail-closed: 테넌트를 실어 오지 못한 state 는 소비하지 않고 무효로 취급한다(jOOQ 구현과 같음).
    if (row.tenantId() == null) {
      log.error("oauth_state 에 tenant_id 가 없다 — 콜백을 진행할 수 없다 (state 앞 8자={})", prefix(state));
      return Optional.empty();
    }
    if (mapper.markConsumed(state, OffsetDateTime.now(ZoneOffset.UTC)) == 0) {
      return Optional.empty();
    }
    return Optional.of(
        new ConsumedState(row.userId(), ChannelType.valueOf(row.channelType()), row.tenantId()));
  }

  /** state 는 CSRF 비밀값이므로 로그에 전문을 남기지 않는다. 추적에 필요한 앞부분만 남긴다. */
  private static String prefix(String state) {
    if (state == null) return "null";
    return state.length() <= 8 ? state : state.substring(0, 8);
  }

  @Override
  public int deleteExpired() {
    return mapper.deleteExpired(OffsetDateTime.now(ZoneOffset.UTC));
  }
}
