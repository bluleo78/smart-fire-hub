package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.analytics.repository.AnalyticsWorkspaceRepository;
import com.smartfirehub.analytics.service.AnalyticsAssetKind;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.util.List;
import java.util.Optional;

/**
 * AnalyticsWorkspaceRepository 의 MyBatis 구현(WD-710) — {@code JooqAnalyticsWorkspaceRepository} 와 같은
 * 의미.
 *
 * <p>작성자 조건 없이 도는 관리자 경로라 작성자 경로 리포지토리와 섞지 않는다. 테넌트는 RLS 에만 기대지 않고 인자를 명시 술어로 건다(스펙 D1). 트랜잭션 속성은
 * jOOQ 구현 그대로 클래스 단위 트랜잭션이 없다.
 *
 * <p>표 이름은 {@link AnalyticsAssetKind#table()} 을 그대로 넘긴다 — XML 이 이 값을 {@code ${table}} 로 SQL 에 끼워
 * 넣지만, enum 생성자에 박힌 컴파일 시점 상수(dashboard·chart·saved_query)뿐이라 사용자 입력이 닿을 수 없다. jOOQ 판도 같은 값으로 표를
 * 고른다.
 */
@PlatformMyBatisRepository
public class MyBatisAnalyticsWorkspaceRepository implements AnalyticsWorkspaceRepository {

  private final AnalyticsWorkspaceMapper mapper;

  public MyBatisAnalyticsWorkspaceRepository(AnalyticsWorkspaceMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<WorkspaceItemRow> findWorkspaceRows(AnalyticsAssetKind kind, long tenantId) {
    return mapper.selectRows(kind.table(), tenantId);
  }

  @Override
  public Optional<WorkspaceItemRow> findWorkspaceRow(
      AnalyticsAssetKind kind, Long id, long tenantId) {
    return Optional.ofNullable(mapper.selectRow(kind.table(), id, tenantId));
  }

  @Override
  public boolean unshareInTenant(AnalyticsAssetKind kind, Long id, long tenantId) {
    return mapper.unshare(kind.table(), id, tenantId) > 0;
  }

  /** 관리자 삭제 — 실제로 지웠는지 돌려준다(WD-616: 동시 삭제에서 진 쪽 판정용). */
  @Override
  public boolean deleteInTenant(AnalyticsAssetKind kind, Long id, long tenantId) {
    return mapper.delete(kind.table(), id, tenantId) > 0;
  }
}
