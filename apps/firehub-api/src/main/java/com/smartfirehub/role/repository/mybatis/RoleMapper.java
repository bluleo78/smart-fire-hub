package com.smartfirehub.role.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.role.dto.RoleResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * role·role_permission·user_role MyBatis 매퍼(WD-706). SQL 은 {@code mybatis/role/RoleMapper.xml}. 세
 * 테이블 모두 테넌트 정책 대상이라 tenant_id 는 문장에 쓰지 않는다(DEFAULT + RLS).
 */
@PlatformMapper
public interface RoleMapper {

  /** 잠금한 행의 수정 시각 — 행은 있는데 시각이 비어 있는 경우를 행 없음과 구분하려고 id 를 함께 읽는다. */
  record VersionRow(Long id, LocalDateTime updatedAt) {}

  /** 사용자별 역할 조회의 한 줄. */
  record UserRoleRow(Long userId, Long id, String name, String description, boolean isSystem) {}

  List<RoleResponse> selectAll();

  RoleResponse selectById(@Param("id") Long id);

  RoleResponse selectByName(@Param("name") String name);

  int countByName(@Param("name") String name);

  List<Long> selectExistingIds(@Param("ids") List<Long> ids);

  /** 역할을 넣고 생성된 id 를 {@code insert.id} 에 채운다. */
  int insert(RoleInsert insert);

  int update(
      @Param("id") Long id,
      @Param("name") String name,
      @Param("description") String description,
      @Param("now") LocalDateTime now);

  /** FOR UPDATE 로 행을 잠근다. 결과 행은 쓰지 않는다. */
  List<Long> lockById(@Param("id") Long id);

  /** 수정 시각을 읽는다(행이 없거나 비면 null). */
  LocalDateTime selectUpdatedAt(@Param("id") Long id);

  /** 수정 시각을 FOR UPDATE 와 함께 읽는다. 행이 없으면 null. */
  VersionRow lockVersionById(@Param("id") Long id);

  List<Long> selectUserIdsByRoleId(@Param("roleId") Long roleId);

  int deleteById(@Param("id") Long id);

  int insertPermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);

  int deletePermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);

  int deleteAllPermissions(@Param("roleId") Long roleId);

  List<RoleResponse> selectByUserId(@Param("userId") Long userId);

  List<UserRoleRow> selectByUserIds(@Param("userIds") List<Long> userIds);
}
