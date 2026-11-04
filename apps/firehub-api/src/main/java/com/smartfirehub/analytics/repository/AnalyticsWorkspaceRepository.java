package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.service.AnalyticsAssetKind;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 워크스페이스 분석 자산 관리(analytics:admin, WD-199) 전용 조회·변경.
 *
 * <p>구현은 {@code JooqAnalyticsWorkspaceRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface AnalyticsWorkspaceRepository {

  /**
   * 관리자 목록·단건 판정용 최소 행 — 자산 내용은 담지 않는다.
   *
   * @param ownerId 작성자 id(created_by)
   * @param ownerName 작성자 이름(계정이 지워졌으면 null)
   */
  record WorkspaceItemRow(
      Long id,
      String name,
      Long ownerId,
      String ownerName,
      boolean isShared,
      LocalDateTime updatedAt) {}

  /** 이 워크스페이스의 해당 종류 자산 전부(최근 수정 순). 작성자 이름은 같은 질의에서 조인해 N+1 을 피한다. */
  List<WorkspaceItemRow> findWorkspaceRows(AnalyticsAssetKind kind, long tenantId);

  /** 이 워크스페이스의 자산 한 건(최소 행). 다른 워크스페이스·없는 자산이면 빈 값 — 호출자가 404 로 바꾼다. */
  Optional<WorkspaceItemRow> findWorkspaceRow(AnalyticsAssetKind kind, Long id, long tenantId);

  /**
   * 관리자 공유 해제 — is_shared 만 false 로 바꾼다. updated_at·updated_by 는 건드리지 않는다: 내용 편집이 아니라 관리 행위라 작성자의
   * 수정 이력처럼 보이면 안 되고, 누가 했는지는 감사 로그가 남긴다.
   */
  void unshareInTenant(AnalyticsAssetKind kind, Long id, long tenantId);

  /**
   * 관리자 삭제 — 작성자와 무관하게 이 워크스페이스의 자산을 지운다. 작성자 삭제(deleteById)와 같은 단일 DELETE 라 DB ON DELETE CASCADE
   * 결과(쿼리→차트→위젯, 차트→위젯, 대시보드→위젯)가 작성자 삭제와 똑같다.
   */
  void deleteInTenant(AnalyticsAssetKind kind, Long id, long tenantId);
}
