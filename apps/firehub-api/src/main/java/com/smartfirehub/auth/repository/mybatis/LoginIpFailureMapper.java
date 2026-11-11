package com.smartfirehub.auth.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import org.apache.ibatis.annotations.Param;

/**
 * login_ip_failures MyBatis 매퍼(WD-706). SQL 은 {@code mybatis/auth/LoginIpFailureMapper.xml}. 만료 판정은
 * 모두 DB 시계다(JVM 시계를 바인딩하지 않는다 — WD-235 원칙).
 */
@PlatformMapper
public interface LoginIpFailureMapper {

  /** (IP 키, 계정) 쌍을 넣거나, 이미 있으면(만료 행 포함) 만료만 DB 시계 기준 15분 뒤로 민다. */
  int upsertFailure(@Param("ipKey") String ipKey, @Param("username") String username);

  /** 만료되지 않은 행 수(PK 가 (ip_key, username) 이라 서로 다른 계정 수와 같다). */
  int countActive(@Param("ipKey") String ipKey);

  /** 만료된 행을 지운다. */
  int deleteExpired();
}
