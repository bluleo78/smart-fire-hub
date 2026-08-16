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

  /**
   * 소비된 state 가 실어 나른 값.
   *
   * <p>{@code tenantId} 는 CSRF 와 무관한 <b>테넌트 운반 값</b>이다(V106 [R7]). OAuth 콜백은
   * permitAll 이라 Bearer 헤더가 없고 따라서 {@code TenantContext} 도 없다 — 발급 시점(인증된
   * {@code /auth-url} 요청)의 테넌트를 여기 실어 두고 콜백이 되찾는 것이 유일한 복원 경로다.
   * Kakao 는 워크스페이스 개념이 없어 {@code team_id} 같은 대안 식별자조차 없으므로 <b>이 경로가
   * 유일한 해법</b>이다.
   */
  record ConsumedState(long userId, ChannelType channelType, long tenantId) {}
}
