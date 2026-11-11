package com.smartfirehub.permission.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.repository.PermissionRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PermissionRepository 의 MyBatis 구현(WD-706) — {@code JooqPermissionRepository} 와 같은 의미.
 *
 * <p>jOOQ 구현이 {@code @Transactional} 을 달지 않으므로 여기서도 달지 않는다. permission 은 전역이고
 * role_permission·user_role 은 테넌트 정책 대상이라, 역할·사용자 단위 조회는 호출자가 테넌트 컨텍스트가 심긴 트랜잭션 안에서 불러야 한다(테넌트가 없으면
 * 정책이 행을 숨긴다 — PG 와 같다).
 */
@PlatformMyBatisRepository
public class MyBatisPermissionRepository implements PermissionRepository {

  private final PermissionMapper mapper;

  public MyBatisPermissionRepository(PermissionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<PermissionResponse> findAll() {
    return mapper.selectAll();
  }

  @Override
  public List<PermissionResponse> findByCategory(String category) {
    return mapper.selectByCategory(category);
  }

  @Override
  public Optional<PermissionResponse> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id));
  }

  @Override
  public Optional<PermissionResponse> findByCode(String code) {
    return Optional.ofNullable(mapper.selectByCode(code));
  }

  @Override
  public List<PermissionResponse> findByRoleId(Long roleId) {
    return mapper.selectByRoleId(roleId);
  }

  @Override
  public Set<String> findCodesByIds(Collection<Long> ids) {
    Set<String> result = new HashSet<>();
    for (List<Long> chunk : InChunks.of(ids)) {
      result.addAll(mapper.selectCodesByIds(chunk));
    }
    return result;
  }

  @Override
  public boolean existsInCategory(Collection<Long> ids, String category) {
    for (List<Long> chunk : InChunks.of(ids)) {
      if (mapper.countInCategory(chunk, category) > 0) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Map<Long, Set<String>> findCodesByRoleIds(Collection<Long> roleIds) {
    Map<Long, Set<String>> result = new HashMap<>();
    for (List<Long> chunk : InChunks.of(roleIds)) {
      for (PermissionMapper.RoleCodeRow row : mapper.selectCodesByRoleIds(chunk)) {
        result.computeIfAbsent(row.roleId(), k -> new HashSet<>()).add(row.code());
      }
    }
    return result;
  }

  @Override
  public Set<String> findPermissionCodesByUserId(Long userId) {
    return new HashSet<>(mapper.selectCodesByUserId(userId));
  }

  @Override
  public Set<Long> findActiveMemberIdsWithPermission(long tenantId, String code) {
    return new HashSet<>(mapper.selectActiveMemberIdsWithPermission(tenantId, code));
  }
}
