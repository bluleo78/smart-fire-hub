package com.smartfirehub.platform.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.platform.repository.PlatformRoleRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * PlatformRoleRepository 의 MyBatis 구현(WD-706) — {@code JooqPlatformRoleRepository} 와 같은 의미.
 *
 * <p>클래스 레벨 {@code @Transactional(readOnly = true)} 는 jOOQ 구현 그대로다 — 인증 필터처럼 주변 트랜잭션이 없는 곳에서 불려도
 * 커넥션 획득과 조회를 한 단위로 묶는다. 플랫폼 RBAC 테이블은 정책 없는 전역 테이블이라 테넌트 컨텍스트가 비어 있어도(운영자 토큰) 결과가 같다. 쓰기 {@link
 * #grantPlatformRole}(WD-748) 은 메서드 레벨 {@code @Transactional} 로 readOnly 를 덮는다.
 */
@PlatformMyBatisRepository
@Transactional(readOnly = true)
public class MyBatisPlatformRoleRepository implements PlatformRoleRepository {

  private final PlatformRoleMapper mapper;

  public MyBatisPlatformRoleRepository(PlatformRoleMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Set<String> findPlatformPermissionCodes(Long userId) {
    // jOOQ fetchSet 과 같이 변경 가능한 집합을 돌려준다(호출처가 집합을 그대로 권한 목록으로 쓴다).
    return new LinkedHashSet<>(mapper.selectPermissionCodes(userId));
  }

  @Override
  public boolean hasAnyPlatformRole(Long userId) {
    return mapper.countPlatformRoles(userId) > 0;
  }

  /**
   * 이름으로 찾은 역할을 준다 — 넣은 행 수로 역할 존재를 판정한다(없으면 0행 → false).
   *
   * <p>클래스 레벨 readOnly 를 덮어 쓰기 트랜잭션으로 돈다(PG 는 읽기 전용 트랜잭션의 INSERT 를 거부). 주변 트랜잭션이 있으면 참여한다.
   */
  @Override
  @Transactional
  public boolean grantPlatformRole(Long userId, String roleName) {
    return mapper.insertPlatformUserRoleByName(userId, roleName) > 0;
  }
}
