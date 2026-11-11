package com.smartfirehub.tenant.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 전역 tenant 테이블 조회와 테넌트 기본값 시드 루틴 호출 MyBatis 매퍼(WD-706). SQL 은 {@code
 * mybatis/tenant/TenantMapper.xml}. tenant 는 테넌트 경계 위의 전역 테이블이라 정책·DEFAULT 가 없다.
 */
@PlatformMapper
public interface TenantMapper {

  /** ACTIVE 테넌트 id(오름차순). */
  List<Long> selectActiveIds();

  /** 그 id 의 ACTIVE 테넌트 행 수(0 또는 1). */
  int countActive(@Param("tenantId") Long tenantId);

  /** 기본 역할·권한매핑·양식·분류·등급 시드 루틴을 부른다(PG 함수, Tibero FH_TENANT 패키지 프로시저). */
  void provisionDefaults(@Param("tenantId") Long tenantId);
}
