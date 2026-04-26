package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;
import java.time.Instant;
import java.util.Optional;

/** OAuth 시작 시 발급한 state(CSRF) 저장·소비. single-use, TTL 10분. */
public interface OAuthStateRepository {
  /** 신규 state 저장. */
  void create(String state, long userId, ChannelType channelType, Instant expiresAt);

  /** 미소비·미만료 state를 반환하며 consumed_at 마킹. single-use 보장. */
  Optional<ConsumedState> consume(String state);

  /** 만료된 state 삭제. 반환=삭제 행 수. 일일 cleanup 잡에서 호출. */
  int deleteExpired();

  record ConsumedState(long userId, ChannelType channelType) {}
}
