package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformRetries;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * UserChannelBindingRepository 의 MyBatis 구현(WD-709) — {@code JooqUserChannelBindingRepository} 와 같은
 * 의미.
 *
 * <p>클래스 단위 {@code @Transactional} 은 jOOQ 구현 그대로이며 지우면 안 된다 — 트랜잭션이 없으면 RLS 컨텍스트가 심기지 않아 조회가 조용히
 * 0행이 되고 INSERT 의 tenant_id DEFAULT 가 비어 실패한다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisUserChannelBindingRepository implements UserChannelBindingRepository {

  private final UserChannelBindingMapper mapper;

  public MyBatisUserChannelBindingRepository(UserChannelBindingMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<UserChannelBinding> findActive(long userId, ChannelType channelType) {
    return Optional.ofNullable(mapper.selectActive(userId, channelType.name()));
  }

  /**
   * binding upsert. workspace_id 가 있으면 유일 제약(uk_user_channel)이 맞는 충돌 대상이라 PG 는 ON CONFLICT, Tibero
   * 는 MERGE 로 갱신한다 — Tibero MERGE 는 같은 키가 동시에 처음 들어오면 한쪽이 중복 키로 실패할 수 있어 한 번 다시 시도한다(다시 돌면 MATCHED
   * 갱신). workspace_id 가 NULL(EMAIL·KAKAO)이면 유일 제약이 NULL 을 서로 다른 값으로 보므로(PG NULLS DISTINCT, Tibero
   * CASE 함수 색인) 충돌이 없고 매번 새 행이다 — 두 DB 모두 단순 INSERT 다.
   */
  @Override
  public void upsert(UserChannelBinding b) {
    if (b.workspaceId() == null) {
      mapper.insertWithoutWorkspace(
          b.userId(),
          b.channelType().name(),
          b.externalUserId(),
          b.displayAddress(),
          b.accessTokenEnc(),
          b.refreshTokenEnc(),
          b.tokenExpiresAt(),
          b.status(),
          b.lastVerifiedAt(),
          b.createdAt(),
          b.updatedAt());
      return;
    }
    PlatformRetries.runOnceOnDuplicateKey(
        () ->
            mapper.upsert(
                b.userId(),
                b.channelType().name(),
                b.workspaceId(),
                b.externalUserId(),
                b.displayAddress(),
                b.accessTokenEnc(),
                b.refreshTokenEnc(),
                b.tokenExpiresAt(),
                b.status(),
                b.lastVerifiedAt(),
                b.createdAt(),
                b.updatedAt(),
                OffsetDateTime.now(ZoneOffset.UTC)));
  }

  @Override
  public List<UserChannelBinding> findByUser(long userId) {
    return mapper.selectByUser(userId);
  }

  @Override
  public void revoke(long userId, ChannelType channelType, Long workspaceId) {
    mapper.revoke(userId, channelType.name(), workspaceId, OffsetDateTime.now(ZoneOffset.UTC));
  }

  @Override
  public Optional<UserChannelBinding> findByExternalId(String teamId, String externalUserId) {
    return Optional.ofNullable(mapper.selectByExternalId(teamId, externalUserId));
  }
}
