package com.smartfirehub.securitylevel.repository.jooq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.AiCall;
import com.smartfirehub.securitylevel.ai.AiCallContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 데이터셋 가시성 도메인 값 → jOOQ 술어 변환(WD-485)을 고정한다.
 *
 * <p>규칙 자체(Policy 와의 일치)는 DatasetAccessGuardTest 매트릭스가 본다. 여기서는 Condition 을 도메인 값으로 바꾸면서 생길 수 있는 회귀
 * — 제한 없음의 SQL, AI 문맥 캡처 시점, 이름 관례 오버로드의 골든 SQL, null 자격 거부 — 만 본다. 렌더는 질의를 실행하지 않고 PG 방언으로 문자열만
 * 비교한다. 스프링 컨텍스트(IntegrationTestBase)는 실제 가드·AI 문맥 빈으로 캡처 시점을 보기 위해서만 띄운다.
 */
class JooqDatasetVisibilityTest extends IntegrationTestBase {

  /** 렌더 전용 컨텍스트 — 질의를 실행하지 않고 인라인 SQL 문자열만 만든다. */
  private static final DSLContext RENDER = DSL.using(SQLDialect.POSTGRES);

  @Autowired private DatasetAccessGuard guard;
  @Autowired private AiCallContext aiCallContext;

  /** 허용 목록·관리자 분기까지 렌더되는 일반 자격(역할 2개, 비관리자). */
  private static final Clearance VIEWER =
      new Clearance(7L, 1L, 2, Set.of(11L, 12L), false, Set.of());

  private static String render(Condition c) {
    return RENDER.renderInlined(c);
  }

  /** ① 제한 없음은 예전 저장 쿼리·차트가 넘기던 trueCondition 과 같은 SQL 이다. */
  @Test
  void unrestricted_rendersSameAsTrueCondition() {
    assertThat(DatasetVisibility.UNRESTRICTED.unrestricted()).isTrue();
    assertThat(render(JooqDatasetVisibility.condition(DatasetVisibility.UNRESTRICTED)))
        .isEqualTo(render(DSL.trueCondition()));
    assertThat(
            render(
                JooqDatasetVisibility.condition(
                    DatasetVisibility.UNRESTRICTED,
                    field(name("d", "id"), Long.class),
                    field(name("d", "security_level_id"), Long.class))))
        .isEqualTo(render(DSL.trueCondition()));
  }

  /** ② AI 범위 밖에서 캡처한 가시성은 나중에 AI 범위 안에서 렌더해도 비AI SQL 이다(렌더 시점 문맥을 다시 읽지 않는다). */
  @Test
  void capturedOutsideAiScope_rendersNonAiSqlEvenInsideAiScope() {
    DatasetVisibility captured = guard.visibility(VIEWER);
    assertThat(captured.aiCall()).isNull();
    String nonAi = render(JooqDatasetVisibility.condition(new DatasetVisibility(VIEWER, null)));

    String renderedInsideScope =
        aiCallContext.callWith(
            new AiCall(ProviderHosting.EXTERNAL, true),
            () -> {
              // 앵커: 이 범위에서 새로 캡처하면 AI 문맥이 잡힌다 — 범위가 실제로 켜져 있다는 증거.
              assertThat(guard.visibility(VIEWER).aiCall()).isNotNull();
              return render(JooqDatasetVisibility.condition(captured));
            });

    assertThat(renderedInsideScope).isEqualTo(nonAi).doesNotContain("ai_policy");
  }

  /** ③ AI 범위 안에서 캡처하면 등급 ai_policy(+공유 목적이면 share_policy) 술어가 실린다 — 범위를 벗어나 렌더해도 그대로다. */
  @Test
  void capturedInsideAiScope_includesAiPolicyPredicate() {
    DatasetVisibility captured =
        aiCallContext.callWith(
            new AiCall(ProviderHosting.SELF_HOSTED, true), () -> guard.visibility(VIEWER));

    assertThat(captured.aiCall()).isEqualTo(new AiCall(ProviderHosting.SELF_HOSTED, true));
    String sql = render(JooqDatasetVisibility.condition(captured));
    assertThat(sql).contains("ai_policy").contains("share_policy").contains("SELF_HOSTED_ONLY");
  }

  /**
   * ④ 이름 관례 오버로드의 골든 SQL — 이동 전 가드가 내던 문자열을 그대로 고정한다(본문을 글자 그대로 옮겼으므로 지금 렌더가 곧 예전 렌더다). 역할 id 는 하나만
   * 둔다 — Set.of 의 순회 순서는 JVM 마다 달라 IN 목록 순서가 흔들린다.
   */
  @Test
  void conventionOverload_rendersGoldenSql() {
    Clearance oneRole = new Clearance(7L, 1L, 2, Set.of(11L), false, Set.of());
    String visibleBody =
        "exists (select 1 as \"one\" from \"public\".\"security_level\" as \"vis_sl\" where"
            + " (\"vis_sl\".\"id\" = \"dataset\".\"security_level_id\" and \"vis_sl\".\"rank\" <= 2"
            + " and (\"vis_sl\".\"allowlist_required\" = false or false or exists (select 1 as"
            + " \"one\" from \"public\".\"dataset_access_grant\" as \"acl_g\" where"
            + " (\"acl_g\".\"dataset_id\" = \"dataset\".\"id\" and (\"acl_g\".\"user_id\" = 7 or"
            + " \"acl_g\".\"role_id\" in (11)))))";

    // 비AI — VIEW 규칙만.
    assertThat(render(JooqDatasetVisibility.condition(new DatasetVisibility(oneRole, null))))
        .isEqualTo(visibleBody + "))");
    // AI(외부 호스팅) + 공유 목적 — ai_policy·share_policy 술어가 붙는다.
    assertThat(
            render(
                JooqDatasetVisibility.condition(
                    new DatasetVisibility(oneRole, new AiCall(ProviderHosting.EXTERNAL, true)))))
        .isEqualTo(
            visibleBody
                + " and \"vis_sl\".\"ai_policy\" = 'ALL' and \"vis_sl\".\"share_policy\" ="
                + " 'ALLOW'))");
    // 역할 없음(NO_RANK) — 아무것도 보이지 않는다.
    assertThat(
            render(
                JooqDatasetVisibility.condition(
                    new DatasetVisibility(Clearance.none(7L, 1L), null))))
        .isEqualTo("false");
  }

  /** ⑤ null 자격은 제한 없음으로 새지 않는다(fail-closed) — 가드도 값 생성자도 거부한다. 제한 없음은 UNRESTRICTED 로만 만든다. */
  @Test
  void nullClearance_isRejected() {
    assertThatThrownBy(() -> guard.visibility(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DatasetVisibility(null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DatasetVisibility(VIEWER, null, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new DatasetVisibility(VIEWER, null).unrestricted()).isFalse();
  }
}
