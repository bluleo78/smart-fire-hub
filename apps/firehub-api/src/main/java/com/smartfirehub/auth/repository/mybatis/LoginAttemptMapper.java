package com.smartfirehub.auth.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * login_attempts MyBatis 매퍼(WD-706). SQL 은 {@code mybatis/auth/LoginAttemptMapper.xml}.
 *
 * <p>문장 1:1 바인딩만 한다. 증가는 RETURNING 이 없는 Tibero 와 문장을 맞추려고 "UPSERT → 조회" 두 문장으로 나눈다.
 */
@PlatformMapper
public interface LoginAttemptMapper {

  /** 신규면 attempts=1, 기존이면 +1 하고 만료를 DB 시계 기준 15분 뒤로 민다(rolling window). */
  int upsertAttempt(@Param("username") String username);

  /** 만료와 무관하게 현재 카운터 값(방금 UPSERT 한 행을 읽는 용도). 없으면 null. */
  Integer selectAttempts(@Param("username") String username);

  /** 만료되지 않은 카운터. 없거나 만료됐으면 null. */
  Integer selectActiveAttempts(@Param("username") String username, @Param("now") LocalDateTime now);

  int deleteByUsername(@Param("username") String username);

  int deleteExpired(@Param("now") LocalDateTime now);
}
