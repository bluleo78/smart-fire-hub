package com.smartfirehub.auth.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * refresh_token MyBatis 매퍼(WD-706). SQL 은 {@code mybatis/auth/RefreshTokenMapper.xml}.
 *
 * <p>문장 1:1 바인딩만 한다. 시각은 jOOQ 구현과 같이 자바 {@link LocalDateTime} 으로 넘긴다(UTC JVM 전제).
 */
@PlatformMapper
public interface RefreshTokenMapper {

  int insert(
      @Param("userId") Long userId,
      @Param("tokenHash") String tokenHash,
      @Param("expiresAt") LocalDateTime expiresAt,
      @Param("familyId") UUID familyId,
      @Param("plane") String plane);

  /** 폐기되지 않고 {@code now} 이후에 만료되는 토큰 수. */
  int countValid(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

  int countRevoked(@Param("tokenHash") String tokenHash);

  /** family_id 를 36자 문자열로 읽는다(PG uuid·Tibero VARCHAR2(36) 공통). 없으면 null. */
  String selectFamilyId(@Param("tokenHash") String tokenHash);

  int revokeByTokenHash(@Param("tokenHash") String tokenHash);

  int revokeByFamilyId(@Param("familyId") UUID familyId);

  int revokeAllByUserIdAndPlane(@Param("userId") Long userId, @Param("plane") String plane);

  int revokeAllByUserId(@Param("userId") Long userId);

  /** 만료됐거나, 폐기되고 생성 뒤 {@code revokedBefore} 이전인 토큰을 지운다. */
  int deleteExpired(
      @Param("now") LocalDateTime now, @Param("revokedBefore") LocalDateTime revokedBefore);
}
