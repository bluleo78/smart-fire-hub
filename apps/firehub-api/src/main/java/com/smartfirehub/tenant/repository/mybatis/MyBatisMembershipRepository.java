package com.smartfirehub.tenant.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.dto.TenantMembership;
import com.smartfirehub.tenant.dto.UnavailableWorkspace;
import com.smartfirehub.tenant.repository.MembershipRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MembershipRepository 의 MyBatis 구현(WD-706) — {@code JooqMembershipRepository} 와 같은 의미.
 *
 * <p>jOOQ 구현이 {@code @Transactional} 을 달지 않으므로 여기서도 달지 않는다 — 각 문장은 호출자 트랜잭션에 참여하고 없으면 자동 커밋이다.
 * membership·tenant·user 는 정책 없는 전역 테이블이라 테넌트 컨텍스트 없이(로그인·테넌트 선택 전) 불려도 결과가 같다.
 */
@PlatformMyBatisRepository
public class MyBatisMembershipRepository implements MembershipRepository {

  private final MembershipMapper mapper;

  public MyBatisMembershipRepository(MembershipMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<MembershipResponse> findActiveByUser(Long userId) {
    return mapper.selectActiveByUser(userId);
  }

  @Override
  public List<UnavailableWorkspace> findUnavailableByUser(Long userId) {
    // 테넌트가 ACTIVE 가 아니면 멤버십 상태와 무관하게 워크스페이스 정지가 우선한다(jOOQ 구현과 같은 판정).
    return mapper.selectUnavailableByUser(userId).stream()
        .map(
            r ->
                new UnavailableWorkspace(
                    r.tenantName(),
                    "ACTIVE".equals(r.tenantStatus())
                        ? UnavailableWorkspace.Reason.MEMBERSHIP_SUSPENDED
                        : UnavailableWorkspace.Reason.WORKSPACE_SUSPENDED))
        .toList();
  }

  @Override
  public boolean hasActiveMembership(Long userId, Long tenantId) {
    return mapper.countActiveMembership(userId, tenantId) > 0;
  }

  @Override
  public void createDefaultMembership(Long userId) {
    insertMember(userId, DEFAULT_TENANT_ID);
  }

  @Override
  public Optional<TenantMembership> findInTenant(Long userId, long tenantId) {
    return Optional.ofNullable(mapper.selectInTenant(userId, tenantId));
  }

  @Override
  public Map<Long, TenantMembership> findInTenant(long tenantId, Collection<Long> userIds) {
    Map<Long, TenantMembership> result = new HashMap<>();
    for (List<Long> chunk : InChunks.of(userIds)) {
      mapper.selectInTenantBatch(tenantId, chunk).forEach(m -> result.put(m.userId(), m));
    }
    return result;
  }

  @Override
  public Set<Long> findActiveMemberIds(long tenantId, Collection<Long> userIds) {
    Set<Long> result = new HashSet<>();
    for (List<Long> chunk : InChunks.of(userIds)) {
      result.addAll(mapper.selectActiveMemberIds(tenantId, chunk));
    }
    return result;
  }

  @Override
  public void insertMember(Long userId, long tenantId) {
    mapper.insertMember(userId, tenantId);
  }

  @Override
  public int updateStatus(Long userId, long tenantId, String status) {
    return mapper.updateStatus(userId, tenantId, status);
  }

  @Override
  public int delete(Long userId, long tenantId) {
    return mapper.delete(userId, tenantId);
  }
}
