package com.smartfirehub.analytics.service;

import com.smartfirehub.analytics.dto.AnalyticsAdminItemResponse;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.AnalyticsWorkspaceRepository;
import com.smartfirehub.analytics.repository.AnalyticsWorkspaceRepository.WorkspaceItemRow;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워크스페이스 분석 자산 관리(analytics:admin) — WD-199.
 *
 * <p>대시보드·차트·저장 쿼리의 수정·삭제는 작성자 한정이라, 작성자가 정지·제거되면 그가 공유한 자산은 다른 사람에게 계속 보이고 실행되는데 아무도(관리자도) 공유를
 * 해제하거나 지울 수 없었다. 이 서비스는 관리자가 워크스페이스 전체 자산을 <b>최소 정보로</b> 보고 <b>공유 해제하거나 삭제</b>만 하게 한다(WD-192 스마트
 * 작업 관리와 같은 B안).
 *
 * <ul>
 *   <li>열람(내용)·편집·실행은 여기 없다 — 작성자의 데이터 권한으로 만든 SQL·차트 설정·위젯 구성이 관리자에게 새지 않게 한다. 작성자 경로(단건 조회·데이터)는
 *       관리자에게도 그대로 404 다.
 *   <li>공유 해제는 대상 자산 하나만 바꾼다. 대시보드를 공유할 때 작성자 본인 위젯 차트·쿼리를 함께 공유하는 규칙(WD-194)의 역방향은 하지 않는다 — 그
 *       차트·쿼리는 다른 대시보드에서도 쓰일 수 있어, 관리자가 하나씩 보고 정한다.
 *   <li>삭제는 작성자 삭제와 같은 단일 DELETE 라 CASCADE 결과(쿼리→차트→위젯, 차트→위젯, 대시보드→위젯)가 같다. 작성자 삭제에 참조 때문에 막는 규칙이
 *       없으므로 관리자도 막지 않고, 대신 삭제 확인 창에 함께 사라지는 항목을 보인다({@link #getDeleteImpact}).
 *   <li>워크스페이스 경계: 모든 조회·변경에 현재 테넌트 tenant_id 를 명시한다(RLS 와 이중). 다른 워크스페이스 자산은 404.
 *   <li>공유 해제·삭제는 감사 로그를 남긴다(남의 자산을 바꾸는 관리 행위라 누가 했는지 추적해야 한다).
 * </ul>
 *
 * <p>작성자 서비스(AnalyticsDashboardService·ChartService·SavedQueryService)와 분리한 이유: 그 서비스들은 단위 테스트가
 * 생성자를 직접 부르고, 관리자 경로는 감사·멤버십 의존이 따로 있어 섞으면 작성자 경로 변경과 관리자 경로 변경이 서로를 흔든다(WD-192 와 같은 판단).
 */
@Service
@RequiredArgsConstructor
public class AnalyticsAdminService {

  private final AnalyticsWorkspaceRepository workspaceRepository;
  private final ChartRepository chartRepository;
  private final AnalyticsDashboardRepository dashboardRepository;
  private final MembershipRepository membershipRepository;
  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  /**
   * 워크스페이스 전체 자산 목록(최소 정보). 질의 수는 자산 수와 무관하다 — 자산+작성자 이름 1회, 작성자 활성 여부 배치 1회.
   *
   * <p>RLS 가 걸린 분석 테이블을 읽으므로 트랜잭션 안에서 돈다(GUC 미설정이면 조용히 0행).
   */
  @Transactional(readOnly = true)
  public List<AnalyticsAdminItemResponse> getWorkspaceItems(AnalyticsAssetKind kind) {
    long tenantId = TenantContext.require("워크스페이스 분석 자산 목록");
    List<WorkspaceItemRow> rows = workspaceRepository.findWorkspaceRows(kind, tenantId);
    if (rows.isEmpty()) {
      return List.of();
    }
    // 작성자 상태는 멤버십 화면·스마트 작업 실행 자격과 같은 「계정 활성 + ACTIVE 멤버 + 테넌트 ACTIVE」 판정을 배치로 쓴다
    Set<Long> activeOwners =
        membershipRepository.findActiveMemberIds(
            tenantId, rows.stream().map(WorkspaceItemRow::ownerId).distinct().toList());
    return rows.stream()
        .map(
            row ->
                new AnalyticsAdminItemResponse(
                    row.id(),
                    row.name(),
                    row.ownerId(),
                    row.ownerName(),
                    activeOwners.contains(row.ownerId()),
                    row.isShared(),
                    row.updatedAt()))
        .toList();
  }

  /** 관리자 공유 해제 — 대상 자산 하나만 비공개로 돌린다. 이미 비공개여도 성공(멱등)이며 감사는 남긴다(WD-192 끄기와 같은 규칙). */
  @Transactional
  public void unshare(AnalyticsAssetKind kind, Long id, Long adminUserId) {
    long tenantId = TenantContext.require("분석 자산 관리자 공유 해제");
    WorkspaceItemRow item = requireWorkspaceItem(kind, id, tenantId);
    workspaceRepository.unshareInTenant(kind, id, tenantId);
    audit(
        adminUserId, kind, kind.unshareAction(), item, kind.label() + " 관리자 공유 해제: " + item.name());
  }

  /** 관리자 삭제 — 작성자 삭제와 같은 CASCADE 로 연결된 차트·위젯도 함께 사라진다. */
  @Transactional
  public void delete(AnalyticsAssetKind kind, Long id, Long adminUserId) {
    long tenantId = TenantContext.require("분석 자산 관리자 삭제");
    WorkspaceItemRow item = requireWorkspaceItem(kind, id, tenantId);
    workspaceRepository.deleteInTenant(kind, id, tenantId);
    audit(adminUserId, kind, kind.deleteAction(), item, kind.label() + " 관리자 삭제: " + item.name());
  }

  /**
   * 관리자 삭제 시 함께 사라지는 항목(WD-86 삭제 영향 범위와 같은 응답). 작성자 경로의 영향 조회는 소유자 관문(404)이라 관리자가 쓸 수 없어 따로 둔다.
   *
   * <p>계산은 작성자 경로와 같은 리포지토리 메서드를 관리자를 조회자로 해서 부른다 — 개수는 가시성과 무관하게 전부, 이름은 관리자에게 보이는 것(본인 소유이거나 공유된
   * 것)만. 관리자 권한이 남의 비공개 자산 이름을 열어 주지 않는다. 대시보드는 위젯만 함께 지워지므로(다른 자산 영향 없음) 차트·쿼리만 지원한다.
   */
  @Transactional(readOnly = true)
  public DeleteImpactResponse getDeleteImpact(AnalyticsAssetKind kind, Long id, Long adminUserId) {
    long tenantId = TenantContext.require("분석 자산 관리자 삭제 영향");
    requireWorkspaceItem(kind, id, tenantId);
    return switch (kind) {
      case CHART ->
          DeleteImpactResponse.forChart(
              dashboardRepository.findImpactByChartIds(List.of(id), adminUserId));
      case QUERY -> {
        List<DeleteImpactResponse.ChartRawRef> charts =
            chartRepository.findImpactRefsBySavedQueryId(id, adminUserId);
        List<Long> chartIds = charts.stream().map(DeleteImpactResponse.ChartRawRef::id).toList();
        yield DeleteImpactResponse.forQuery(
            charts, dashboardRepository.findImpactByChartIds(chartIds, adminUserId));
      }
      case DASHBOARD -> throw new IllegalArgumentException("대시보드는 삭제 영향 조회를 지원하지 않습니다");
    };
  }

  /** 이 워크스페이스 자산이 아니면(없음·다른 워크스페이스) 작성자 경로와 같은 404. */
  private WorkspaceItemRow requireWorkspaceItem(AnalyticsAssetKind kind, Long id, long tenantId) {
    return workspaceRepository
        .findWorkspaceRow(kind, id, tenantId)
        .orElseThrow(() -> kind.notFound());
  }

  /**
   * 감사 로그(resource = 테이블 이름, resourceId = 자산 id). 행위자 username 은 감사 테이블 NOT NULL 이라 조회해서 넣는다 —
   * ProactiveJobAdminService.audit 와 같은 방식. 메타데이터에는 누구의 어떤 자산이었는지만 남기고 내용(SQL·설정)은 싣지 않는다.
   */
  private void audit(
      Long adminUserId,
      AnalyticsAssetKind kind,
      String action,
      WorkspaceItemRow item,
      String description) {
    String adminName =
        userRepository.findById(adminUserId).map(UserResponse::username).orElse("unknown");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", item.name());
    metadata.put("ownerUserId", item.ownerId());
    metadata.put("ownerName", item.ownerName());
    metadata.put("wasShared", item.isShared());
    auditLogService.log(
        adminUserId,
        adminName,
        action,
        kind.table(),
        String.valueOf(item.id()),
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }
}
