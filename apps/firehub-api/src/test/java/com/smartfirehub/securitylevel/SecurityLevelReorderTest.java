package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
