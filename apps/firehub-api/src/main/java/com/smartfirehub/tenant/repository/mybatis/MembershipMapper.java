package com.smartfirehub.tenant.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.dto.TenantMembership;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * membership(+tenant·user 조인) MyBatis 매퍼(WD-706). SQL 은 {@code
 * mybatis/tenant/MembershipMapper.xml}.
 *
 * <p>membership·tenant·user 는 모두 정책 없는 전역 테이블이라, 인터페이스가 테넌트를 인자로 받는 문장은 tenant_id 를 직접 건다 — 빠뜨리면 한
 * 테넌트의 관리자가 다른 테넌트 멤버십을 바꾼다.
 */
@PlatformMapper
public interface MembershipMapper {

  /** 들어갈 수 없는 워크스페이스 한 줄 — 사유는 테넌트 상태로 자바에서 정한다. */
  record UnavailableRow(String tenantName, String tenantStatus) {}

  List<MembershipResponse> selectActiveByUser(@Param("userId") Long userId);

  List<UnavailableRow> selectUnavailableByUser(@Param("userId") Long userId);

  int countActiveMembership(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

  TenantMembership selectInTenant(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

  List<TenantMembership> selectInTenantBatch(
      @Param("tenantId") Long tenantId, @Param("userIds") List<Long> userIds);

  List<Long> selectActiveMemberIds(
      @Param("tenantId") Long tenantId, @Param("userIds") List<Long> userIds);

  int insertMember(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

  int updateStatus(
      @Param("userId") Long userId,
      @Param("tenantId") Long tenantId,
      @Param("status") String status);

  int delete(@Param("userId") Long userId, @Param("tenantId") Long tenantId);
}
