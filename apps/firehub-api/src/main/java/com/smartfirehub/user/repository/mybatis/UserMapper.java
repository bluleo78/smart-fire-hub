package com.smartfirehub.user.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.user.dto.UserResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * user·user_role·membership MyBatis 매퍼(WD-706). SQL 은 {@code mybatis/user/UserMapper.xml}.
 *
 * <p>문장 1:1 바인딩만 한다. user 는 전역 테이블, user_role 은 테넌트 정책 대상(tenant_id 는 DEFAULT 가 채운다).
 */
@PlatformMapper
public interface UserMapper {

  UserResponse selectByUsername(@Param("username") String username);

  UserResponse selectById(@Param("id") Long id);

  /** 소문자로 정규화된 username 과 대소문자 무시로 맞는 계정 중 id 가 가장 작은 하나. */
  UserResponse selectByUsernameIgnoreCase(@Param("lowered") String lowered);

  String selectPasswordByUsername(@Param("username") String username);

  String selectPasswordById(@Param("id") Long id);

  int countByUsername(@Param("username") String username);

  int countAny();

  /** 사용자를 넣고 생성된 id 를 {@code insert.id} 에 채운다. */
  int insert(UserInsert insert);

  List<UserResponse> selectPage(
      @Param("tenantId") Long tenantId,
      @Param("pattern") String pattern,
      @Param("limit") int limit,
      @Param("offset") long offset);

  List<UserResponse> selectActiveMembersByIds(
      @Param("tenantId") Long tenantId, @Param("ids") List<Long> ids);

  long countInTenant(@Param("tenantId") Long tenantId, @Param("pattern") String pattern);

  int update(
      @Param("id") Long id,
      @Param("name") String name,
      @Param("email") String email,
      @Param("now") LocalDateTime now);

  int updatePassword(
      @Param("id") Long id, @Param("password") String password, @Param("now") LocalDateTime now);

  /** 이미 활성이면 0 행. */
  int activate(@Param("id") Long id, @Param("now") LocalDateTime now);

  /** 이미 비활성이면 0 행. */
  int deactivate(@Param("id") Long id, @Param("now") LocalDateTime now);

  int countActiveAdmins(@Param("tenantId") Long tenantId);

  List<Long> selectActiveAdminIds(@Param("tenantId") Long tenantId);

  int deleteRolesInTenant(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

  int countAdminRole(@Param("userId") Long userId);

  int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

  /**
   * 한 사용자에게 역할 여러 개를 한 문장으로 넣는다(WD-737) — PG 는 다중 행 VALUES, Tibero 는 INSERT ALL. 호출자가 {@code
   * roleIds} 를 {@code InChunks.INSERT_ROWS} 행 이하로 나눠 부른다(INSERT ALL 컬럼 상한·바인드 여유). 중복 id 는 PK 위반이다.
   */
  int insertUserRoles(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);

  int deleteUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

  int deleteAllUserRoles(@Param("userId") Long userId);
}
