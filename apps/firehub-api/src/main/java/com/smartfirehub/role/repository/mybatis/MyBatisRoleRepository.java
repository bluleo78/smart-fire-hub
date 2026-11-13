package com.smartfirehub.role.repository.mybatis;

import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.repository.RoleRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * RoleRepository 의 MyBatis 구현(WD-706) — {@code JooqRoleRepository} 와 같은 의미.
 *
 * <p>jOOQ 구현이 {@code @Transactional} 을 달지 않으므로 여기서도 달지 않는다 — role·user_role·role_permission 은 테넌트
 * 정책 대상이라 호출자가 테넌트 컨텍스트가 심긴 트랜잭션 안에서 불러야 한다(lockById·lockUpdatedAtById 의 FOR UPDATE 도 호출자 트랜잭션이 잠금
 * 범위다). 빈 문자열은 변환하지 않는다(controller Ruling) — Tibero 는 {@code ''} 를 NULL 로 저장해 설명은 null 로 돌아오고 NOT
 * NULL 인 이름은 거절된다.
 */
@PlatformMyBatisRepository
public class MyBatisRoleRepository implements RoleRepository {

  private final RoleMapper mapper;

  public MyBatisRoleRepository(RoleMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<RoleResponse> findAll() {
    return mapper.selectAll();
  }

  @Override
  public Optional<RoleResponse> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id));
  }

  @Override
  public Optional<RoleResponse> findByName(String name) {
    return Optional.ofNullable(mapper.selectByName(name));
  }

  @Override
  public boolean existsByName(String name) {
    return mapper.countByName(name) > 0;
  }

  @Override
  public Set<Long> findExistingIds(Collection<Long> ids) {
    Set<Long> result = new HashSet<>();
    for (List<Long> chunk : InChunks.of(ids)) {
      result.addAll(mapper.selectExistingIds(chunk));
    }
    return result;
  }

  @Override
  public RoleResponse save(String name, String description) {
    RoleInsert row = new RoleInsert(name, description);
    mapper.insert(row);
    return mapper.selectById(row.getId());
  }

  @Override
  public void update(Long id, String name, String description) {
    mapper.update(id, name, description, LocalDateTime.now());
  }

  @Override
  public void lockById(Long id) {
    mapper.lockById(id);
  }

  @Override
  public LocalDateTime findUpdatedAtById(Long id) {
    return mapper.selectUpdatedAt(id);
  }

  // WD-575: 이름·설명 수정의 편집 기준 버전 비교용 — 권한 교체(lockById)와 같은 FOR UPDATE 행 잠금이라 둘도 서로 직렬화된다.
  @Override
  public Optional<LockedRowVersion> lockUpdatedAtById(Long id) {
    return Optional.ofNullable(mapper.lockVersionById(id))
        .map(row -> new LockedRowVersion(row.updatedAt()));
  }

  @Override
  public List<Long> findUserIdsByRoleId(Long roleId) {
    return mapper.selectUserIdsByRoleId(roleId);
  }

  @Override
  public int deleteById(Long id) {
    // 지운 행 수를 그대로 돌려준다 — 동시 삭제에서 진 쪽(0행)을 서비스가 404 로 가르기 위해서다(WD-582).
    return mapper.deleteById(id);
  }

  @Override
  public void addPermission(Long roleId, Long permissionId) {
    mapper.insertPermission(roleId, permissionId);
  }

  @Override
  public void removePermission(Long roleId, Long permissionId) {
    mapper.deletePermission(roleId, permissionId);
  }

  /**
   * 기존 권한을 지우고 다시 넣는다(같은 호출자 트랜잭션 안). 빈 목록이면 지우기만 한다. 넣기는 청크마다 한 문장(PG 다중 행 VALUES·Tibero INSERT
   * ALL)이다. 중복 제거를 하지 않는다 — 행별 삽입·jOOQ 판처럼 중복 id 는 PK 위반으로 실패해야 한다.
   */
  @Override
  public void setPermissions(Long roleId, List<Long> permissionIds) {
    mapper.deleteAllPermissions(roleId);
    for (List<Long> chunk : InChunks.ordered(permissionIds, InChunks.INSERT_ROWS)) {
      mapper.insertPermissions(roleId, chunk);
    }
  }

  @Override
  public List<RoleResponse> findByUserId(Long userId) {
    return mapper.selectByUserId(userId);
  }

  @Override
  public Map<Long, List<RoleResponse>> findByUserIds(List<Long> userIds) {
    if (userIds == null) {
      return Map.of();
    }
    Map<Long, List<RoleResponse>> result = new LinkedHashMap<>();
    for (List<Long> chunk : InChunks.of(userIds)) {
      for (RoleMapper.UserRoleRow row : mapper.selectByUserIds(chunk)) {
        result
            .computeIfAbsent(row.userId(), k -> new ArrayList<>())
            .add(new RoleResponse(row.id(), row.name(), row.description(), row.isSystem()));
      }
    }
    return result;
  }
}
