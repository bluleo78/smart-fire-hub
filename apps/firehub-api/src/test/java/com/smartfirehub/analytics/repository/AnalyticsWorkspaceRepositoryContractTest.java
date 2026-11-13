package com.smartfirehub.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.repository.AnalyticsWorkspaceRepository.WorkspaceItemRow;
import com.smartfirehub.analytics.service.AnalyticsAssetKind;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * AnalyticsWorkspaceRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-710).
 *
 * <p>관리자 경로라 작성자 조건 없이 그 워크스페이스(tenant_id 명시, 스펙 D1)의 자산 전부를 다룬다. 세 종류(대시보드·차트·저장 쿼리)를 모두 돌려 표 이름
 * 분기가 실제로 맞는지 본다. 지킬 것: 다른 테넌트의 행은 목록·단건에 섞이지 않고 공유 해제·삭제로 바뀌지 않는다(호출 테넌트와 인자 테넌트가 엇갈려도), 공유 해제는
 * is_shared 만 바꾸고 updated_at 은 그대로, 이미 비공개여도 일치 행으로 true, 정렬은 updated_at DESC, id DESC.
 */
public abstract class AnalyticsWorkspaceRepositoryContractTest extends TenantPairContractBase {

  protected abstract AnalyticsWorkspaceRepository repository();

  private long userA;
  private long userA2;
  private long userB;

  @BeforeEach
  void createUsers() {
    userA = fx.createUser(prefix + "-a", null);
    fx.setUserName(userA, prefix + "-a-name");
    userA2 = fx.createUser(prefix + "-a2", null);
    fx.setUserName(userA2, prefix + "-a2-name");
    userB = fx.createUser(prefix + "-b", null);
  }

  /** 종류별 자산 하나를 만든다(차트는 공유 저장 쿼리를 하나 더 만들어 잇는다). */
  private long create(AnalyticsAssetKind kind, long tenant, long user, boolean shared) {
    return switch (kind) {
      case DASHBOARD -> fx.createDashboard(tenant, user, shared);
      case CHART ->
          fx.createChart(
              tenant, user, fx.createSavedQuery(tenant, user, prefix + "-csq", true, null), shared);
      case QUERY -> fx.createSavedQuery(tenant, user, prefix + "-q", shared, null);
    };
  }

  /** 픽스처가 만든 자산의 이름(종류별 고정값). */
  private String nameOf(AnalyticsAssetKind kind) {
    return switch (kind) {
      case DASHBOARD -> "fx-d";
      case CHART -> "fx-c";
      case QUERY -> prefix + "-q";
    };
  }

  private void setUpdatedAt(AnalyticsAssetKind kind, long id, LocalDateTime at) {
    switch (kind) {
      case DASHBOARD -> fx.setDashboardUpdatedAt(id, at);
      case CHART -> fx.setChartUpdatedAt(id, at);
      case QUERY -> fx.setSavedQueryUpdatedAt(id, at);
    }
  }

  private WorkspaceItemRow rowA(AnalyticsAssetKind kind, long id) {
    return inA(() -> repository().findWorkspaceRow(kind, id, tenantA)).orElseThrow();
  }

  private WorkspaceItemRow rowB(AnalyticsAssetKind kind, long id) {
    return inB(() -> repository().findWorkspaceRow(kind, id, tenantB)).orElseThrow();
  }

  @ParameterizedTest
  @EnumSource(AnalyticsAssetKind.class)
  @DisplayName(
      "findWorkspaceRows — 그 테넌트의 자산 전부(작성자·공유 무관)를 작성자 이름과 함께 updated_at DESC, id DESC 로, 다른 테넌트는 섞이지 않는다")
  void findWorkspaceRows_tenantOnlyAndOrdered(AnalyticsAssetKind kind) {
    long a1 = create(kind, tenantA, userA, false);
    long a2 = create(kind, tenantA, userA2, true);
    long a3 = create(kind, tenantA, userA, false);
    long a4 = create(kind, tenantA, userA2, false);
    long b1 = create(kind, tenantB, userB, true);
    LocalDateTime base = LocalDateTime.of(2026, 2, 1, 9, 0, 0, 123456000);
    setUpdatedAt(kind, a1, base.plusHours(1));
    setUpdatedAt(kind, a2, base);
    setUpdatedAt(kind, a3, base);
    setUpdatedAt(kind, a4, base.minusHours(1));

    List<WorkspaceItemRow> rows = inA(() -> repository().findWorkspaceRows(kind, tenantA));
    assertThat(rows).extracting(WorkspaceItemRow::id).containsExactly(a1, a3, a2, a4);

    WorkspaceItemRow first = rows.get(0);
    assertThat(first.name()).isEqualTo(nameOf(kind));
    assertThat(first.ownerId()).isEqualTo(userA);
    assertThat(first.ownerName()).isEqualTo(prefix + "-a-name");
    assertThat(first.isShared()).isFalse();
    assertThat(first.updatedAt()).isEqualTo(base.plusHours(1));
    WorkspaceItemRow shared = rows.get(2);
    assertThat(shared.ownerId()).isEqualTo(userA2);
    assertThat(shared.ownerName()).isEqualTo(prefix + "-a2-name");
    assertThat(shared.isShared()).isTrue();

    assertThat(inB(() -> repository().findWorkspaceRows(kind, tenantB)))
        .extracting(WorkspaceItemRow::id)
        .containsExactly(b1);
    assertThat(inA(() -> repository().findWorkspaceRows(kind, tenantB)))
        .as("테넌트 인자가 다르면 아무것도 섞이지 않는다")
        .isEmpty();
    assertThat(inB(() -> repository().findWorkspaceRows(kind, tenantA))).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(AnalyticsAssetKind.class)
  @DisplayName("findWorkspaceRow — 그 테넌트의 자산 한 건, 다른 테넌트 자산·엇갈린 테넌트 인자·없는 id 는 empty")
  void findWorkspaceRow_tenantOnly(AnalyticsAssetKind kind) {
    long a = create(kind, tenantA, userA2, true);
    long b = create(kind, tenantB, userB, true);

    WorkspaceItemRow r = rowA(kind, a);
    assertThat(r.id()).isEqualTo(a);
    assertThat(r.name()).isEqualTo(nameOf(kind));
    assertThat(r.ownerId()).isEqualTo(userA2);
    assertThat(r.ownerName()).isEqualTo(prefix + "-a2-name");
    assertThat(r.isShared()).isTrue();
    assertThat(r.updatedAt()).isNotNull();

    assertThat(inA(() -> repository().findWorkspaceRow(kind, b, tenantA))).isEmpty();
    assertThat(inA(() -> repository().findWorkspaceRow(kind, a, tenantB))).isEmpty();
    assertThat(inB(() -> repository().findWorkspaceRow(kind, a, tenantA))).isEmpty();
    assertThat(inA(() -> repository().findWorkspaceRow(kind, -1L, tenantA))).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(AnalyticsAssetKind.class)
  @DisplayName(
      "unshareInTenant — is_shared 만 끄고 updated_at 은 그대로, 이미 비공개도 true, 다른 테넌트 행은 바뀌지 않는다, 없는 id 는 false")
  void unshareInTenant_onlyThatTenant(AnalyticsAssetKind kind) {
    long a = create(kind, tenantA, userA2, true);
    long b = create(kind, tenantB, userB, true);
    LocalDateTime stamp = LocalDateTime.of(2026, 3, 1, 8, 0, 0, 654321000);
    setUpdatedAt(kind, a, stamp);

    assertThat(inA(() -> repository().unshareInTenant(kind, a, tenantB))).isFalse();
    assertThat(inA(() -> repository().unshareInTenant(kind, b, tenantA))).isFalse();
    assertThat(inB(() -> repository().unshareInTenant(kind, a, tenantA))).isFalse();
    assertThat(rowA(kind, a).isShared()).isTrue();
    assertThat(rowB(kind, b).isShared()).as("다른 테넌트 행은 그대로").isTrue();

    assertThat(inA(() -> repository().unshareInTenant(kind, a, tenantA))).isTrue();
    WorkspaceItemRow r = rowA(kind, a);
    assertThat(r.isShared()).isFalse();
    assertThat(r.updatedAt()).as("관리 행위라 수정 시각을 건드리지 않는다").isEqualTo(stamp);

    assertThat(inA(() -> repository().unshareInTenant(kind, a, tenantA)))
        .as("이미 비공개여도 일치 행이라 true(멱등)")
        .isTrue();
    assertThat(inA(() -> repository().unshareInTenant(kind, -1L, tenantA))).isFalse();
    assertThat(rowB(kind, b).isShared()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(AnalyticsAssetKind.class)
  @DisplayName("deleteInTenant — 작성자와 무관하게 그 테넌트 자산만 지우고, 다른 테넌트 행은 지우지 않는다, 두 번째는 false")
  void deleteInTenant_onlyThatTenant(AnalyticsAssetKind kind) {
    long a = create(kind, tenantA, userA2, false);
    long kept = create(kind, tenantA, userA, false);
    long b = create(kind, tenantB, userB, false);

    assertThat(inA(() -> repository().deleteInTenant(kind, a, tenantB))).isFalse();
    assertThat(inA(() -> repository().deleteInTenant(kind, b, tenantA))).isFalse();
    assertThat(inB(() -> repository().deleteInTenant(kind, a, tenantA))).isFalse();
    assertThat(inA(() -> repository().findWorkspaceRow(kind, a, tenantA))).isPresent();

    assertThat(inA(() -> repository().deleteInTenant(kind, a, tenantA))).isTrue();
    assertThat(inA(() -> repository().findWorkspaceRow(kind, a, tenantA))).isEmpty();
    assertThat(inA(() -> repository().findWorkspaceRow(kind, kept, tenantA))).isPresent();
    assertThat(inB(() -> repository().findWorkspaceRow(kind, b, tenantB)))
        .as("다른 테넌트 행은 그대로")
        .isPresent();
    assertThat(inA(() -> repository().deleteInTenant(kind, a, tenantA))).isFalse();
    assertThat(inA(() -> repository().deleteInTenant(kind, -1L, tenantA))).isFalse();
  }
}
