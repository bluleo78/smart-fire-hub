package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.AUDIT_LOG;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.securitylevel.dto.ReorderPreviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 스펙 §4.7 "순서 변경: 영향(역할별 열람 데이터셋 증감 수) 확인 후 적용, 감사". */
class SecurityLevelReorderTest extends SecurityLevelServiceTest {

  @Test
  void preview_reportsPerRoleDatasetDelta_withoutChangingAnything() {
    insertDataset("민감");
    insertDataset("민감");
    insertDataset("공개");
    // USER(내부) 기준: 공개·내부만 본다. 민감을 내부 아래로 옮기면 USER 가 민감 2개를 더 본다.
    List<Long> order = List.of(levelId("공개"), levelId("민감"), levelId("내부"), levelId("기밀"));
    ReorderPreviewResponse p = asTenant(() -> service.previewReorder(order));
    assertThat(p.roles())
        .filteredOn(r -> r.roleName().equals("USER"))
        .singleElement()
        .extracting(ReorderPreviewResponse.RoleImpact::datasetDelta)
        .isEqualTo(2L);
    // ADMIN 은 항상 최상위라 증감 없음 → 목록에 없다.
    assertThat(p.roles()).noneMatch(r -> r.roleName().equals("ADMIN"));
    int sensitiveRank =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(SECURITY_LEVEL.RANK)
                    .from(SECURITY_LEVEL)
                    .where(SECURITY_LEVEL.NAME.eq("민감"))
                    .fetchSingle(SECURITY_LEVEL.RANK));
    assertThat(sensitiveRank).as("미리보기는 변경하지 않는다").isEqualTo(3);
  }

  @Test
  void apply_swapsRanks_keepsAdminTop_andAudits() {
    List<Long> order = List.of(levelId("공개"), levelId("민감"), levelId("기밀"), levelId("내부"));
    asTenant(() -> service.applyReorder(order, actor));
    List<String> names =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(SECURITY_LEVEL.NAME)
                    .from(SECURITY_LEVEL)
                    .orderBy(SECURITY_LEVEL.RANK.asc())
                    .fetch(SECURITY_LEVEL.NAME));
    assertThat(names).containsExactly("공개", "민감", "기밀", "내부");
    Long adminLevel =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(ROLE.MAX_SECURITY_LEVEL_ID)
                    .from(ROLE)
                    .where(ROLE.NAME.eq("ADMIN"))
                    .fetchSingle(ROLE.MAX_SECURITY_LEVEL_ID));
    assertThat(adminLevel).isEqualTo(levelId("내부"));
    assertThat(auditCount("SECURITY_LEVEL_REORDER")).isEqualTo(1);
  }

  @Test
  void apply_rejectsPartialOrForeignIdList() {
    List<Long> partial = List.of(levelId("공개"), levelId("내부"));
    assertThatThrownBy(() -> asTenant(() -> service.applyReorder(partial, actor)))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("SECURITY_LEVEL_ORDER_INVALID");
  }

  private static long userDelta(ReorderPreviewResponse p) {
    return p.roles().stream()
        .filter(r -> r.roleName().equals("USER"))
        .mapToLong(ReorderPreviewResponse.RoleImpact::datasetDelta)
        .sum();
  }

  /** 손실 방향 — USER(내부)가 공개보다 낮아지면 공개 데이터셋을 잃는다(음수 증감). */
  @Test
  void preview_reportsNegativeDelta_whenRoleLosesVisibility() {
    insertDataset("공개");
    insertDataset("공개");
    List<Long> order = List.of(levelId("내부"), levelId("공개"), levelId("민감"), levelId("기밀"));
    ReorderPreviewResponse p = asTenant(() -> service.previewReorder(order));
    assertThat(userDelta(p)).isEqualTo(-2L);
  }

  /** 허용 목록 필요 등급(기밀)은 rank 만 올라서는 보이지 않는다 — 허용 항목 없으면 증감 0. */
  @Test
  void preview_allowlistLevelWithoutGrant_isNotCounted() {
    insertDataset("기밀");
    List<Long> order = List.of(levelId("기밀"), levelId("공개"), levelId("내부"), levelId("민감"));
    ReorderPreviewResponse p = asTenant(() -> service.previewReorder(order));
    assertThat(p.roles()).noneMatch(r -> r.roleName().equals("USER"));
  }

  /** 같은 상황에서 그 역할의 ROLE 허용 항목이 있으면 센다(사용자 항목은 귀속하지 않는다). */
  @Test
  void preview_allowlistLevelWithRoleGrant_isCounted() {
    long ds = insertDataset("기밀");
    insertDataset("기밀"); // 허용 항목 없는 다른 데이터셋은 세지 않는다
    long userRole = roleId("USER");
    inTenantFixture(
        tenantId,
        () ->
            dsl.insertInto(DATASET_ACCESS_GRANT)
                .set(DATASET_ACCESS_GRANT.DATASET_ID, ds)
                .set(DATASET_ACCESS_GRANT.ROLE_ID, userRole)
                .execute());
    inTenantFixture(
        tenantId,
        () ->
            dsl.insertInto(DATASET_ACCESS_GRANT)
                .set(DATASET_ACCESS_GRANT.DATASET_ID, ds)
                .set(DATASET_ACCESS_GRANT.USER_ID, actor)
                .execute());
    List<Long> order = List.of(levelId("기밀"), levelId("공개"), levelId("내부"), levelId("민감"));
    ReorderPreviewResponse p = asTenant(() -> service.previewReorder(order));
    assertThat(userDelta(p)).isEqualTo(1L);
  }

  /** 감사 메타의 impact 는 미리보기 결과와 같아야 한다. */
  @Test
  void apply_auditImpactEqualsPreview() throws Exception {
    insertDataset("민감");
    insertDataset("민감");
    List<Long> order = List.of(levelId("공개"), levelId("민감"), levelId("내부"), levelId("기밀"));
    ReorderPreviewResponse preview = asTenant(() -> service.previewReorder(order));
    asTenant(() -> service.applyReorder(order, actor));
    String meta =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(AUDIT_LOG.METADATA)
                    .from(AUDIT_LOG)
                    .where(AUDIT_LOG.ACTION_TYPE.eq("SECURITY_LEVEL_REORDER"))
                    .fetchSingle(AUDIT_LOG.METADATA)
                    .data());
    JsonNode impact = new ObjectMapper().readTree(meta).get("impact");
    assertThat(preview.roles()).isNotEmpty();
    assertThat(impact).hasSize(preview.roles().size());
    for (int i = 0; i < impact.size(); i++) {
      assertThat(impact.get(i).get("roleId").asLong()).isEqualTo(preview.roles().get(i).roleId());
      assertThat(impact.get(i).get("datasetDelta").asLong())
          .isEqualTo(preview.roles().get(i).datasetDelta());
    }
  }
}
