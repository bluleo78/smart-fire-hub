package com.smartfirehub.user.repository.mybatis;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformText;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * UserRepository 의 MyBatis 구현(WD-706) — {@code JooqUserRepository} 와 같은 의미.
 *
 * <p>jOOQ 구현이 메서드에 {@code @Transactional} 을 달지 않으므로 여기서도 달지 않는다 — 각 문장은 호출자 트랜잭션에 참여하고 없으면 자동 커밋이다.
 * user 는 전역 테이블이라 조회에 테넌트 컨텍스트가 필요 없다(로그인 경로). 테넌트 경계는 tenantId 인자를 받는 문장이 membership 조인으로 세운다. 빈
 * 문자열은 변환하지 않는다(controller Ruling) — Tibero 는 {@code ''} 를 NULL 로 저장해 NULL 허용 열은 null 로 돌아오고 NOT
 * NULL 열은 거절된다.
 */
@PlatformMyBatisRepository
public class MyBatisUserRepository implements UserRepository {

  private final UserMapper mapper;

  /** 가입·관리자 가드 직렬화 잠금 — PG advisory·Tibero DBMS_LOCK 을 플랫폼 잠금 추상화로 위임한다(WD-486). */
  private final PlatformLocks locks;

  public MyBatisUserRepository(UserMapper mapper, PlatformLocks locks) {
    this.mapper = mapper;
    this.locks = locks;
  }

  @Override
  public Optional<UserResponse> findByUsername(String username) {
    return Optional.ofNullable(mapper.selectByUsername(username));
  }

  @Override
  public Optional<UserResponse> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id));
  }

  @Override
  public String resolveLoginUsername(String input) {
    String trimmed = input.trim();
    if (mapper.countByUsername(trimmed) > 0) {
      return trimmed;
    }
    return findByUsernameIgnoreCase(trimmed)
        .map(UserResponse::username)
        .orElse(trimmed.toLowerCase(Locale.ROOT));
  }

  @Override
  public Optional<String> findPasswordByUsername(String username) {
    return Optional.ofNullable(mapper.selectPasswordByUsername(username));
  }

  @Override
  public Optional<String> findPasswordById(Long id) {
    return Optional.ofNullable(mapper.selectPasswordById(id));
  }

  @Override
  public boolean existsByUsername(String username) {
    return mapper.countByUsername(username) > 0;
  }

  @Override
  public boolean existsAnyUser() {
    return mapper.countAny() > 0;
  }

  @Override
  public void acquireFirstUserLock() {
    locks.xactLock(1L);
  }

  @Override
  public void acquireMemberAdminGuardLock(long tenantId) {
    locks.xactLock("member-admin-guard:" + tenantId);
  }

  @Override
  public UserResponse save(String username, String email, String password, String name) {
    return insert(username, email, password, name, false);
  }

  /** 계정 삽입 공통부 — 생성 키로 받은 id 로 다시 읽어 돌려준다(Tibero 는 RETURNING 이 없다). */
  private UserResponse insert(
      String username, String email, String password, String name, boolean mustChangePassword) {
    UserInsert row = new UserInsert(username, email, password, name, mustChangePassword);
    mapper.insert(row);
    return mapper.selectById(row.getId());
  }

  @Override
  public Optional<UserResponse> findByUsernameIgnoreCase(String username) {
    return Optional.ofNullable(
        mapper.selectByUsernameIgnoreCase(username.toLowerCase(Locale.ROOT)));
  }

  @Override
  public UserResponse saveWithTemporaryPassword(
      String username, String email, String encodedPassword, String name) {
    return insert(username, email, encodedPassword, name, true);
  }

  @Override
  public List<UserResponse> findAllPaginated(long tenantId, String search, int page, int size) {
    return mapper.selectPage(
        tenantId, PlatformText.searchPattern(search), size, (long) page * size);
  }

  @Override
  public List<UserResponse> findByIds(long tenantId, List<Long> ids) {
    if (ids == null) {
      return List.of();
    }
    List<UserResponse> result = new ArrayList<>();
    for (List<Long> chunk : InChunks.of(ids)) {
      result.addAll(mapper.selectActiveMembersByIds(tenantId, chunk));
    }
    return result;
  }

  @Override
  public long countAll(long tenantId, String search) {
    return mapper.countInTenant(tenantId, PlatformText.searchPattern(search));
  }

  @Override
  public boolean update(Long id, String name, String email) {
    return mapper.update(id, name, email, LocalDateTime.now()) > 0;
  }

  @Override
  public void updatePassword(Long id, String encodedPassword) {
    mapper.updatePassword(id, encodedPassword, LocalDateTime.now());
  }

  @Override
  public int setActive(Long id, boolean active) {
    LocalDateTime now = LocalDateTime.now();
    return active ? mapper.activate(id, now) : mapper.deactivate(id, now);
  }

  @Override
  public int countActiveAdmins(long tenantId) {
    return mapper.countActiveAdmins(tenantId);
  }

  @Override
  public List<Long> findActiveAdminIds(long tenantId) {
    return mapper.selectActiveAdminIds(tenantId);
  }

  @Override
  public int deleteRolesInTenant(Long userId, long tenantId) {
    return mapper.deleteRolesInTenant(userId, tenantId);
  }

  @Override
  public boolean hasAdminRole(Long userId) {
    return mapper.countAdminRole(userId) > 0;
  }

  @Override
  public void addRole(Long userId, Long roleId) {
    mapper.insertUserRole(userId, roleId);
  }

  @Override
  public void removeRole(Long userId, Long roleId) {
    mapper.deleteUserRole(userId, roleId);
  }

  /**
   * 기존 배정을 지우고 다시 넣는다(같은 호출자 트랜잭션 안). 빈 목록이면 지우기만 한다. 넣기는 청크마다 한 문장(PG 다중 행 VALUES·Tibero INSERT
   * ALL)이다. 중복 제거를 하지 않는다 — 행별 삽입·jOOQ 판처럼 중복 id 는 PK 위반으로 실패해야 한다.
   */
  @Override
  public void setRoles(Long userId, List<Long> roleIds) {
    mapper.deleteAllUserRoles(userId);
    for (List<Long> chunk : InChunks.ordered(roleIds, InChunks.INSERT_ROWS)) {
      mapper.insertUserRoles(userId, chunk);
    }
  }
}
