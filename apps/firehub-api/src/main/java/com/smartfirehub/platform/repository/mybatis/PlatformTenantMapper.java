package com.smartfirehub.platform.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.platform.dto.TenantMemberResponse;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 운영자 평면 tenant·membership MyBatis 매퍼(WD-706). SQL 은 {@code
 * mybatis/platform/PlatformTenantMapper.xml}.
 *
 * <p>tenant·membership·user 는 정책 없는 전역 테이블이라 테넌트 컨텍스트 없이(운영자 토큰) 읽고 쓴다. 테넌트 경계는 인터페이스가 받는 tenantId
 * 인자가 세운다.
 */
@PlatformMapper
public interface PlatformTenantMapper {

  /** 테넌트를 ACTIVE 로 넣고 생성된 id 를 {@code insert.id} 에 채운다. */
  int insertTenant(TenantInsert insert);

  /** 초기 Owner 멤버십 — 이미 있으면 아무것도 하지 않는다(PG ON CONFLICT, Tibero MERGE). */
  int insertOwnerMembership(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

  int countBySlug(@Param("slug") String slug);

  int countById(@Param("tenantId") Long tenantId);

  List<TenantSummaryResponse> selectAll();

  TenantSummaryResponse selectById(@Param("tenantId") Long tenantId);

  int updateStatus(@Param("tenantId") Long tenantId, @Param("status") String status);

  List<TenantMemberResponse> selectMembers(@Param("tenantId") Long tenantId);
}
