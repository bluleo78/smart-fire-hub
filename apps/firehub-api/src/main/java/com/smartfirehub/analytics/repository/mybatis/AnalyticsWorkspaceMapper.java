package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.analytics.repository.AnalyticsWorkspaceRepository.WorkspaceItemRow;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 워크스페이스 분석 자산 관리(analytics:admin, WD-199) MyBatis 매퍼(WD-710). SQL 은 {@code
 * mybatis/analytics/AnalyticsWorkspaceMapper.xml}.
 *
 * <p>{@code table} 은 SQL 에 그대로 끼워 넣는 표 이름이라 {@code AnalyticsAssetKind.table()}(enum 에 박힌
 * dashboard·chart·saved_query 세 상수)에서만 온다 — 사용자 입력이 아니다. 테넌트는 RLS 와 별개로 인자 {@code tenantId} 를 명시
 * 술어로 건다(스펙 D1).
 */
@PlatformMapper
public interface AnalyticsWorkspaceMapper {

  /** 그 테넌트의 자산 전부 — updated_at DESC, id DESC. */
  List<WorkspaceItemRow> selectRows(@Param("table") String table, @Param("tenantId") long tenantId);

  /** 그 테넌트의 자산 한 건 — 없으면 null. */
  WorkspaceItemRow selectRow(
      @Param("table") String table, @Param("id") Long id, @Param("tenantId") long tenantId);

  /** 공유 해제(is_shared 만) — 일치 행 수. */
  int unshare(@Param("table") String table, @Param("id") Long id, @Param("tenantId") long tenantId);

  /** 삭제 — 지운 행 수. */
  int delete(@Param("table") String table, @Param("id") Long id, @Param("tenantId") long tenantId);
}
