package com.smartfirehub.auth.repository.mybatis;

import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * RefreshTokenRepository 의 MyBatis 구현(WD-706) — {@code JooqRefreshTokenRepository} 와 같은 의미.
 *
 * <p>리프레시는 테넌트가 정해지기 전이고 refresh_token 은 전역 테이블이라 테넌트 컨텍스트가 필요 없다. jOOQ 구현이 메서드에
 * {@code @Transactional} 을 달지 않으므로 여기서도 달지 않는다 — 각 문장은 호출자 트랜잭션이 있으면 거기에 참여하고 없으면 자동 커밋이다. 폐기
 * 단건({@link #revokeByTokenHash})의 "먼저 폐기한 쪽만 true" 는 {@code revoked=false} 조건부 UPDATE 의 영향 행 수로
 * 판정한다(WD-268).
 *
 * <p>빈 문자열 토큰 해시는 {@link EmptyKeys} 센티널로 바꿔 저장·조회한다(Tibero 가 {@code ''} 를 NULL 로 보기 때문).
 */
@PlatformMyBatisRepository
public class MyBatisRefreshTokenRepository implements RefreshTokenRepository {

  /** 폐기된 토큰을 남겨 두는 기간(일) — 재사용 탐지에 쓰이므로 jOOQ 구현과 같은 7일. */
  private static final long REVOKED_RETENTION_DAYS = 7;

  private final RefreshTokenMapper mapper;

  public MyBatisRefreshTokenRepository(RefreshTokenMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void save(Long userId, String tokenHash, LocalDateTime expiresAt, UUID familyId) {
    save(userId, tokenHash, expiresAt, familyId, PLANE_WEB);
  }

  @Override
  public void save(
      Long userId, String tokenHash, LocalDateTime expiresAt, UUID familyId, String plane) {
    mapper.insert(userId, EmptyKeys.key(tokenHash), expiresAt, familyId, plane);
  }

  @Override
  public boolean existsValidToken(String tokenHash) {
    return mapper.countValid(EmptyKeys.key(tokenHash), LocalDateTime.now()) > 0;
  }

  @Override
  public boolean isTokenRevoked(String tokenHash) {
    return mapper.countRevoked(EmptyKeys.key(tokenHash)) > 0;
  }

  @Override
  public Optional<UUID> findFamilyIdByTokenHash(String tokenHash) {
    return Optional.ofNullable(mapper.selectFamilyId(EmptyKeys.key(tokenHash)))
        .map(UUID::fromString);
  }

  @Override
  public boolean revokeByTokenHash(String tokenHash) {
    return mapper.revokeByTokenHash(EmptyKeys.key(tokenHash)) > 0;
  }

  @Override
  public void revokeByFamilyId(UUID familyId) {
    mapper.revokeByFamilyId(familyId);
  }

  @Override
  public void revokeAllWebByUserId(Long userId) {
    mapper.revokeAllByUserIdAndPlane(userId, PLANE_WEB);
  }

  @Override
  public void revokeAllByUserId(Long userId) {
    mapper.revokeAllByUserId(userId);
  }

  @Override
  public int deleteExpiredTokens() {
    LocalDateTime now = LocalDateTime.now();
    return mapper.deleteExpired(now, now.minusDays(REVOKED_RETENTION_DAYS));
  }
}
