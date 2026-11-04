package com.smartfirehub.securitylevel.repository.jooq;

import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.trueCondition;

import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.AiCall;
import org.jooq.Condition;
import org.jooq.Field;

/**
 * {@link DatasetVisibility} 를 jOOQ 술어로 옮기는 정적 유틸 — 리포지토리 구현만 쓴다(WD-485).
 *
 * <p>왜 리포지토리 패키지에 두는가: 가시성 규칙을 SQL 로 옮기는 일은 생성 테이블(security_level)을 참조하는 jOOQ 렌더링이라, 서비스·가드가 아닌
 * 리포지토리 계층의 일이다. 가드에서 옮겨 온 본문이라 렌더 SQL 은 이동 전과 바이트 단위로 같다. {@link
 * com.smartfirehub.securitylevel.access.DatasetAccessPolicy#canView} 와의 일치는 DatasetAccessGuardTest
 * 매트릭스가 고정한다.
 */
public final class JooqDatasetVisibility {

  private JooqDatasetVisibility() {}

  /**
   * DatasetRepository 이름 관례({@code "dataset"."id"}, {@code "dataset"."security_level_id"})의 가시성 술어.
   * 조회 대상 테이블이 {@code "dataset"} 이름으로 조인돼 있어야 한다.
   */
  public static Condition condition(DatasetVisibility v) {
    return condition(
        v,
        field(name("dataset", "id"), Long.class),
        field(name("dataset", "security_level_id"), Long.class));
  }

  /**
   * 주어진 데이터셋 id·등급 컬럼에 거는 가시성 술어. 제한 없음이면 {@code trueCondition}(예전 저장 쿼리·차트가 넘기던 값과 같다), 아니면 생성 시점에
   * 캡처한 AI 문맥으로 렌더한다(렌더 시점의 AI 범위는 보지 않는다).
   */
  public static Condition condition(
      DatasetVisibility v, Field<Long> datasetIdField, Field<Long> levelIdField) {
    if (v.unrestricted()) {
      return trueCondition();
    }
    return render(v.clearance(), datasetIdField, levelIdField, v.aiCall());
  }

  /**
   * 가시성 조건 + (AI 문맥이면) 등급의 ai_policy·share_policy 술어(스펙 §4.3). ai 가 null 이면 VIEW 규칙 그대로다. {@link
   * com.smartfirehub.securitylevel.access.DatasetAccessPolicy#aiAllowedForLevel}·{@link
   * com.smartfirehub.securitylevel.access.DatasetAccessPolicy#shareAllowedForLevel} 와의 일치는
   * DatasetAccessGuardTest#aiVisibility_agreesWithPolicy_acrossHostingAndShare 가 고정한다.
   */
  private static Condition render(
      Clearance c, Field<Long> datasetIdField, Field<Long> levelIdField, AiCall ai) {
    // 역할이 하나도 없으면 아무것도 보이지 않는다.
    if (c.rank() == Clearance.NO_RANK) {
      return falseCondition();
    }
    var sl = SECURITY_LEVEL.as("vis_sl");
    Condition allowlistOk =
        sl.ALLOWLIST_REQUIRED
            .isFalse()
            .or(c.tenantAdmin() ? sl.ADMIN_BYPASS.isTrue() : falseCondition())
            .or(JooqDatasetAccessRepository.onAllowlistCondition(c, datasetIdField));
    // AI 문맥이면 이름·설명까지 LLM 으로 가므로 목록 단계에서 ai_policy(+공유 목적이면 share_policy)로 거른다.
    Condition aiOk = ai == null ? noCondition() : aiPolicyAllows(sl.AI_POLICY, ai.hosting());
    Condition shareOk =
        ai != null && ai.share()
            ? sl.SHARE_POLICY.eq(LevelPolicy.SharePolicy.ALLOW.name())
            : noCondition();
    return exists(
        selectOne()
            .from(sl)
            .where(sl.ID.eq(levelIdField))
            .and(sl.RANK.le(c.rank()))
            .and(allowlistOk)
            .and(aiOk)
            .and(shareOk));
  }

  /**
   * ai_policy 컬럼 술어 — 사용자 없는 경로(임베딩 SQL)도 쓴다. {@link
   * com.smartfirehub.securitylevel.access.DatasetAccessPolicy#aiAllowedForLevel} 과 같은 규칙(hosting
   * null = 외부).
   */
  public static Condition aiPolicyAllows(Field<String> aiPolicyField, ProviderHosting hosting) {
    Condition all = aiPolicyField.eq(LevelPolicy.AiPolicy.ALL.name());
    return hosting == ProviderHosting.SELF_HOSTED
        ? all.or(aiPolicyField.eq(LevelPolicy.AiPolicy.SELF_HOSTED_ONLY.name()))
        : all;
  }
}
