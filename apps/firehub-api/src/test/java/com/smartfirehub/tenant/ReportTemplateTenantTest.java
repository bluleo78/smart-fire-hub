package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 프로비저닝된 신규 테넌트가 원본이 아니라 <b>자기 사본</b>의 내장 양식을 본다는 것을 고정한다.
 *
 * <p>"원본이 안 보인다" 를 다른 테넌트의 행 수로 세려는 유혹이 있는데, RLS 아래에서는 그 조회가
 * 항상 0 이라 단언이 공허해진다. 그래서 각 테넌트를 <b>자기 컨텍스트에서</b> 세고, 두 집합의 id 가
 * 겹치지 않는지(= 복제된 별개 행인지)로 확인한다.
 */
class ReportTemplateTenantTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;
  @Autowired private TenantProvisioningService provisioningService;

  private Long tenantA;

  @AfterEach
  void tearDown() {
    if (tenantA != null) {
      // 프로비저닝이 역할·권한매핑·양식을 함께 심으므로 전부 지워야 tenant FK 가 풀린다.
      // 테스트 커넥션도 비특권 롤이라 정리는 대상 테넌트 컨텍스트 트랜잭션 안에서 한다.
      final long target = tenantA;
      TenantRlsTestSupport.runInTenantTransaction(
          tx, target, () -> TenantRlsTestSupport.deleteRbacCascade(dsl, target));
      TenantRlsTestSupport.deleteTenants(dsl, tenantA);
      tenantA = null;
    }
  }

  @Test
  @DisplayName("프로비저닝한 테넌트는 원본이 아닌 자기 사본의 내장 양식만 본다")
  void tenantSeesOnlyItsOwnBuiltinTemplates() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "tpl");
    provisioningService.provisionDefaults(tenantA);

    // 내장 양식은 컬럼이 아니라 user_id IS NULL 로 표현된다(V98 주석 참조).
    List<Long> sourceIds = builtinIdsIn(DEFAULT_TEST_TENANT_ID);
    List<Long> ownIds = builtinIdsIn(tenantA);

    assertThat(sourceIds).as("원본 테넌트에 내장 양식이 없으면 이 테스트는 공허하다").isNotEmpty();
    assertThat(ownIds).as("신규 테넌트가 사본을 원본과 같은 개수만큼 받아야 한다").hasSameSizeAs(sourceIds);
    assertThat(ownIds)
        .as("사본이 아니라 원본 행이 보이는 것이라면 id 가 겹친다 — 격리가 안 된 것이다")
        .doesNotContainAnyElementsOf(sourceIds);

    // 신규 테넌트 컨텍스트에서 보이는 전체 양식 수 == 자기 행 수. 남의 행이 하나라도 섞이면 깨진다.
    Integer seenByTenant =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            tenantA,
            () -> (Integer) dsl.fetchValue("select count(*)::int from report_template"));
    assertThat(seenByTenant).isEqualTo(ownIds.size());
  }

  /** 해당 테넌트 컨텍스트에서 보이는 내장 양식(user_id IS NULL)의 id 목록을 돌려준다. */
  private List<Long> builtinIdsIn(long tenantId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            dsl.fetch("select id from report_template where user_id is null")
                .getValues("id", Long.class));
  }
}
