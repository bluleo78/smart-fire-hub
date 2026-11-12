package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformRetries;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.UserChannelPreferenceRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * UserChannelPreferenceRepository 의 MyBatis 구현(WD-709) — {@code
 * JooqUserChannelPreferenceRepository} 와 같은 의미.
 *
 * <p>클래스 단위 {@code @Transactional} 은 jOOQ 구현 그대로이며 지우면 안 된다 — 트랜잭션이 없으면 RLS 컨텍스트가 심기지 않아 조회가 0행(기본값
 * true 로 오판)이 되고 INSERT 의 tenant_id DEFAULT 가 비어 실패한다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisUserChannelPreferenceRepository implements UserChannelPreferenceRepository {

  private final UserChannelPreferenceMapper mapper;

  public MyBatisUserChannelPreferenceRepository(UserChannelPreferenceMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean isEnabled(long userId, ChannelType channelType) {
    // CHAT 은 안전망 — DB 조회 생략, 항상 true
    if (channelType == ChannelType.CHAT) return true;
    Boolean enabled = mapper.selectEnabled(userId, channelType.name());
    // 행이 없으면 기본값 true (사용자가 명시적으로 끈 적이 없으면 허용)
    return enabled == null || enabled;
  }

  /**
   * enabled 를 upsert 한다. Tibero MERGE 는 같은 키가 동시에 처음 들어오면 한쪽이 중복 키로 실패할 수 있어(문장 단위 실패라 트랜잭션은 산다) 한
   * 번 다시 시도한다 — 다시 돌면 이미 들어간 행을 만나 MATCHED 갱신이 되어 PG 의 DO UPDATE 와 같은 결과다.
   */
  @Override
  public void setEnabled(long userId, ChannelType channelType, boolean enabled) {
    PlatformRetries.runOnceOnDuplicateKey(() -> mapper.upsert(userId, channelType.name(), enabled));
  }
}
