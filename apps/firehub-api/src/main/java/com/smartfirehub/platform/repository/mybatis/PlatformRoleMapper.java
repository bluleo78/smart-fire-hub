package com.smartfirehub.platform.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 플랫폼 운영자 RBAC(platform_user_role·platform_role_permission·permission) MyBatis 매퍼(WD-706). SQL 은
 * {@code mybatis/platform/PlatformRoleMapper.xml}.
 *
 * <p>세 테이블 모두 테넌트 경계 위의 전역 테이블(tenant_id·정책 없음)이라 테넌트 컨텍스트 없이 읽는다.
 */
@PlatformMapper
public interface PlatformRoleMapper {

  /** 사용자의 플랫폼 권한 코드(중복 제거). */
  List<String> selectPermissionCodes(@Param("userId") Long userId);

  /** 사용자가 가진 플랫폼 롤 수. */
  int countPlatformRoles(@Param("userId") Long userId);
}
