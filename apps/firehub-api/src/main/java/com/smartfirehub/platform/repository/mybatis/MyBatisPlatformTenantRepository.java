package com.smartfirehub.platform.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.platform.dto.TenantMemberResponse;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import com.smartfirehub.platform.repository.PlatformTenantRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

/**
 * PlatformTenantRepository 의 MyBatis 구현(WD-706) — {@code JooqPlatformTenantRepository} 와 같은 의미.
 *
 * <p>jOOQ 구현처럼 {@code @Transactional} 을 달지 않는다 — 테넌트 생성은 여러 쓰기가 원자적이어야 해서 트랜잭션 경계는 서비스가 잡는다.
 * tenant· membership·user 는 정책 없는 전역 테이블이라 운영자 토큰(테넌트 컨텍스트 없음)으로도 전 테넌트가 보인다. RLS 가 걸린 도메인 테이블은
 * 건드리지 않는다.
 *
 * <p>빈 문자열(controller Ruling): 값을 변환하지 않는다 — Tibero 는 {@code ''} 를 NULL 로 저장하므로 slug(NULL 허용)는 NULL
 * 로 들어가고 name·status(NOT NULL)는 거절되거나 조건이 맞지 않는다. API 경계({@code CreateTenantRequest} 의
 * {@code @NotBlank})와 고정 상태값 호출처가 빈 값을 미리 막는다.
 */
@PlatformMyBatisRepository
public class MyBatisPlatformTenantRepository implements PlatformTenantRepository {

  private final PlatformTenantMapper mapper;

  public MyBatisPlatformTenantRepository(PlatformTenantMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public long insertTenant(String slug, String name) {
    TenantInsert row = new TenantInsert(slug, name);
    mapper.insertTenant(row);
    return row.getId();
  }

  @Override
  public void insertOwnerMembership(long tenantId, long userId) {
    try {
      mapper.insertOwnerMembership(tenantId, userId);
    } catch (DuplicateKeyException alreadyInserted) {
      // Tibero MERGE 는 동시 요청 둘이 함께 행이 없다고 보면 뒤 요청이 membership_unique 위반으로 실패한다.
      // 계약은 이미 있으면 그대로 두는 것이라 이 예외를 삼킨다.
      // Tibero 는 문장 단위로 롤백하므로 호출자 트랜잭션은 깨지지 않는다.
      // PG 는 ON CONFLICT DO NOTHING 이라 이 예외가 나지 않는다.
      // PG 에서 삼키면 트랜잭션이 중단 상태로 남으니 PG 문장은 ON CONFLICT 를 유지해야 한다.
    }
  }

  @Override
  public boolean slugExists(String slug) {
    return mapper.countBySlug(slug) > 0;
  }

  @Override
  public boolean existsById(long tenantId) {
    return mapper.countById(tenantId) > 0;
  }

  @Override
  public List<TenantSummaryResponse> findAll() {
    return mapper.selectAll();
  }

  @Override
  public Optional<TenantSummaryResponse> findById(long tenantId) {
    return Optional.ofNullable(mapper.selectById(tenantId));
  }

  @Override
  public int updateStatus(long tenantId, String status) {
    return mapper.updateStatus(tenantId, status);
  }

  @Override
  public List<TenantMemberResponse> findMembers(long tenantId) {
    return mapper.selectMembers(tenantId);
  }
}
