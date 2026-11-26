package com.smartfirehub.auth.service;

import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.RefreshTokenReusedException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 회전 + 재사용 탐지. <b>두 평면이 공유하는 정책</b>이다.
 *
 * <p>{@code refresh_token} 은 전역 테이블이라 테넌트 평면({@code AuthService})과 운영자 평면 ({@code
 * PlatformAuthService})이 같은 회전 기계를 쓴다. 그런데 이 18줄이 두 서비스에 복사돼 있었고 이미 조금씩 어긋나기 시작했다 — 회전 정책이 바뀔 때 한쪽만
 * 고치면 <b>한 평면에서만</b> 탈취 탐지가 느슨해지는데, 그것은 컴파일도 테스트도 알려주지 않는 종류의 어긋남이다. 해시를 {@link RefreshTokenHasher}
 * 로 통일한 것과 같은 이유로 회전도 한 곳에 둔다.
 *
 * <p><b>회전 유예</b>(WD-1141): 탭 사이 잠금이 없는 환경에서는 이긴 탭이 회전을 마친 <b>뒤에</b> 도착한 진 탭의 재제시가 재사용으로 걸려 패밀리 전체를
 * 죽였다 — 세션이 살아 있던 이긴 탭까지 로그아웃되는 결과다. 그래서 회전 직후 {@link #GRACE_WINDOW} 안에 온 재제시는 탈취로 보지 않고 패밀리를
 * 폐기하지 않는다. 유예 중에도 새 토큰은 주지 않으므로 재사용자는 얻을 것이 없고(401 로 끝), 정상 사용자의 동시 갱신만 남는다. 유예 밖은 오늘처럼
 * 패밀리 폐기다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenRotation {

  /**
   * 회전 직후 재제시가 패밀리 폐기로 이어지지 않게 하는 유예 창 (WD-1141).
   *
   * <p>값은 <b>상수</b>다 — 이 창은 보안 정책이라 프로파일·환경에 따라 달라지면 배포마다 탈취 탐지 기준이 달라진다. 5초는 진 탭의 재확인(클라이언트가 이긴
   * 탭의 새 쿠키를 다시 보낼 때까지의 대기)보다 넉넉하고, 재사용자가 유예에 머무를 수 있는 시간은 짧다.
   */
  static final Duration GRACE_WINDOW = Duration.ofSeconds(5);

  /**
   * 토큰을 쓸 수 없을 때 쓰는 공통 거절 문구 — 재사용({@link RefreshTokenReusedException}) 문구와 일부러 다르다.
   *
   * <p>회전 유예(WD-1141) 안의 재제시도 이 문구를 쓴다: 유예의 재제시는 탈취가 아니라 동시 갱신의 진 쪽이라, 재사용
   * 문구를 주면 탈취 탐지와 같은 경로로 보이기 때문이다.
   */
  private static final String INVALID_TOKEN_MESSAGE = "만료되었거나 폐기된 토큰입니다. 다시 로그인해 주세요.";

  private final RefreshTokenRepository refreshTokenRepository;

  /**
   * 제시된 토큰을 폐기하고 그 패밀리 id 를 돌려준다. 새 토큰은 호출자가 같은 패밀리로 발급한다.
   *
   * <p>이미 폐기된 토큰이 다시 오면 탈취를 의심해 <b>패밀리 전체</b>를 폐기한다. 회전 체인이 로그인마다 새로 시작되므로 패밀리 = 하나의 로그인 세션이다.
   * 다만 회전 커밋 직후 {@link #GRACE_WINDOW} 안에 온 재제시는 동시 갱신의 진 쪽으로 보아 패밀리를 폐기하지 않고 401 로만 돌려보낸다(WD-1141).
   *
   * <p>같은 토큰으로 <b>동시에</b> 들어온 두 갱신 중 늦은 쪽(폐기 UPDATE 가 0행)은 거부만 하고 패밀리는 폐기하지 않는다. 둘 다 통과시키면 한 토큰에서
   * 살아 있는 가지가 둘 생기고, 재사용으로 몰면 여러 탭이 부팅하며 동시에 갱신하는 정상 사용자의 세션까지 끊긴다.
   *
   * <p><b>호출자 계약</b>: 패밀리 폐기를 쓴 뒤 {@link RefreshTokenReusedException} 을 던진다. 호출자 트랜잭션이 이 예외로 롤백되면
   * 폐기도 함께 사라져 탐지가 아무것도 막지 못하므로(WD-268), 호출자는 {@code @Transactional(noRollbackFor =
   * RefreshTokenReusedException.class)} 여야 한다. 유예 경로는 폐기를 하지 않으므로 패밀리 폐기 롤백과 무관하다.
   *
   * @param tokenHash {@link RefreshTokenHasher} 로 해시한 값
   * @return 이 토큰이 속한 패밀리 id
   * @throws RefreshTokenReusedException 재사용 — 패밀리 전체를 폐기했다
   * @throws InvalidTokenException 폐기·만료·미존재 — 어느 경우든 다시 로그인해야 한다
   */
  public UUID revokeAndGetFamily(String tokenHash) {
    if (refreshTokenRepository.isTokenRevoked(tokenHash)) {
      if (withinGrace(tokenHash)) {
        // 회전 커밋 직후에 온 재제시 — 동시 갱신에서 진 쪽이다. 패밀리를 죽이면 이긴 탭의 세션까지 로그아웃되므로,
        // 401 을 돌려 클라이언트가 이긴 탭의 새 쿠키로 재확인하게 한다. 새 토큰은 주지 않으므로 재사용자는 얻을 것이 없다.
        // 유예는 패밀리 폐기를 건너뛰므로 이 로그가 재제시가 남기는 유일한 흔적이다(WD-1141 리뷰) —
        // tokenHash 는 해시라 원문이 아니고, 원본 리프레시 토큰은 어디에도 적지 않는다.
        log.warn("회전 유예 내 재사용 — 패밀리 폐기 생략 tokenHash={}", tokenHash);
        throw new InvalidTokenException(INVALID_TOKEN_MESSAGE);
      }
      throw revokeFamily(tokenHash);
    }
    if (!refreshTokenRepository.existsValidToken(tokenHash)) {
      throw new InvalidTokenException(INVALID_TOKEN_MESSAGE);
    }

    UUID familyId =
        refreshTokenRepository
            .findFamilyIdByTokenHash(tokenHash)
            .orElseThrow(() -> new InvalidTokenException("토큰 정보를 찾을 수 없습니다. 다시 로그인해 주세요."));
    if (!refreshTokenRepository.revokeByTokenHash(tokenHash)) {
      // 위 검사와 이 UPDATE 사이에 같은 토큰의 다른 갱신이 먼저 회전했다. 먼저 끝난 쪽만 살린다.
      throw new InvalidTokenException(INVALID_TOKEN_MESSAGE);
    }
    return familyId;
  }

  /**
   * 회전 유예(WD-1141) — 이미 폐기된 토큰이 {@link #GRACE_WINDOW} 안에 재제시됐으면 true.
   *
   * <p>폐기 시각이 비어 있으면(마이그레이션 이전에 폐기된 행) 유예를 적용하지 않는다 — 그때는 오늘처럼 재사용으로 패밀리를 폐기한다.
   *
   * <p>비교 기준은 {@link RefreshTokenRepository#revokedAtNow()} — 폐기를 찍는 시각과 같은 시계다(WD-1141 반려 3차 5). 노드마다 기본
   * 시간대가 다르면 5초 유예가 몇 시간씩 벌어진다.
   *
   * <p>아래 한도({@code revokedAt <= now + GRACE_WINDOW})는 **위쪽**도 막는다: 폐기 시각이 크게 미래로 찍혀 있으면(잘못 적힌 값) 유예 밖
   * 재사용까지 무한히 관용해 패밀리 폐기가 영원히 미뤄진다. 다만 유예를 잴 때와 폐기를 찍을 때가 다른 노드의 시계라 {@code now} 에 바로 대면, 시계가
   * 앞선 쪽이 찍은 폐기 시각이 유예 밖으로 밀려 정상 재제시까지 탈취로 몰린다 — 그래서 한도에 {@link #GRACE_WINDOW} 만큼의 시계 여유를 둔다.
   * 여유까지 넘는 미래 값은 애초에 유예로 세지 않는다.
   */
  private boolean withinGrace(String tokenHash) {
    LocalDateTime now = RefreshTokenRepository.revokedAtNow();
    LocalDateTime windowStart = now.minus(GRACE_WINDOW);
    // 시계 여유: 폐기를 찍은 노드의 시계가 이쪽보다 앞서 있어도 유예로 세지 않게 막지 않는다(5초를 넘는 미래 값은 그대로 밖).
    LocalDateTime windowEnd = now.plus(GRACE_WINDOW);
    return refreshTokenRepository
        .findRevokedAt(tokenHash)
        .filter(revokedAt -> !revokedAt.isBefore(windowStart))
        .filter(revokedAt -> !revokedAt.isAfter(windowEnd))
        .isPresent();
  }

  /** 재사용 대응: 토큰의 패밀리 전체를 폐기하고, 호출자가 던질 예외를 돌려준다. */
  private RefreshTokenReusedException revokeFamily(String tokenHash) {
    refreshTokenRepository
        .findFamilyIdByTokenHash(tokenHash)
        .ifPresent(refreshTokenRepository::revokeByFamilyId);
    return new RefreshTokenReusedException();
  }
}
