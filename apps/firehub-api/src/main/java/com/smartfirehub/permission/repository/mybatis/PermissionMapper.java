package com.smartfirehub.permission.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.permission.dto.PermissionResponse;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * permission 조회 MyBatis 매퍼(WD-706). SQL 은 {@code mybatis/permission/PermissionMapper.xml}.
 * permission 은 전역 카탈로그(정책 없음), role_permission·user_role 은 테넌트 정책 대상이다.
 */
@PlatformMapper
public interface PermissionMapper {

  /** 역할 id 와 권한 코드 한 쌍. */
  record RoleCodeRow(Long roleId, String code) {}

  List<PermissionResponse> selectAll();

  List<PermissionResponse> selectByCategory(@Param("category") String category);

  PermissionResponse selectById(@Param("id") Long id);

  PermissionResponse selectByCode(@Param("code") String code);

  List<PermissionResponse> selectByRoleId(@Param("roleId") Long roleId);

  List<String> selectCodesByIds(@Param("ids") List<Long> ids);

  int countInCategory(@Param("ids") List<Long> ids, @Param("category") String category);

  List<RoleCodeRow> selectCodesByRoleIds(@Param("roleIds") List<Long> roleIds);

  List<String> selectCodesByUserId(@Param("userId") Long userId);

  List<Long> selectActiveMemberIdsWithPermission(
      @Param("tenantId") Long tenantId, @Param("code") String code);
}
