package com.smartfirehub.notification.auth;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.OAuthStateRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * OAuth CSRF state 발급·소비 서비스.
 *
 * <p>OAuth 인증 시작 시 CSPRNG 32바이트 hex state를 발급하여 CSRF 공격을 방어한다. state는 단일 사용(single-use)이며 10분 TTL을
 * 가진다.
 */
@Service
public class OAuthStateService {

  private static final Duration TTL = Duration.ofMinutes(10);
  private static final SecureRandom RNG = new SecureRandom();
  private final OAuthStateRepository repo;

  public OAuthStateService(OAuthStateRepository repo) {
    this.repo = repo;
  }

  /**
   * 32바이트 CSPRNG hex state 생성 후 DB에 저장.
   *
   * @param userId 인증을 시작한 사용자 ID
   * @param channelType 연동할 채널 종류
   * @return redirect_uri에 포함할 state 문자열
   */
  public String issue(long userId, ChannelType channelType) {
    byte[] bytes = new byte[32];
    RNG.nextBytes(bytes);
    String state = HexFormat.of().formatHex(bytes);
    repo.create(state, userId, channelType, Instant.now().plus(TTL));
    return state;
  }

  /**
   * OAuth 콜백에서 state를 소비하며 유효성 검증.
   *
   * <p>미소비·미만료 상태인 경우에만 Optional에 값이 존재한다. 소비 후 재사용 불가(single-use).
   *
   * @param state 콜백으로 전달받은 state 파라미터
   * @return 유효하면 userId·channelType, 무효/만료면 empty
   */
  public Optional<OAuthStateRepository.ConsumedState> consume(String state) {
    return repo.consume(state);
  }
}
