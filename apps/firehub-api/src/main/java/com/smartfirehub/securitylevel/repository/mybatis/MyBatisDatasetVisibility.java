package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.AiCall;
import java.util.List;

/**
 * {@link DatasetVisibility} 를 MyBatis SQL 조각의 평평한 파라미터로 옮기는 정적 유틸(WD-487 PoC).
 *
 * <p>무엇을: 분기 판단(제한 없음·NO_RANK·ADMIN·AI 호스팅·share)을 Java 에서 미리 끝내 불리언·값으로 펼친다. XML 조각({@code
 * mybatis/securitylevel/DatasetVisibility.xml} 의 {@code visible})은 이 값을 {@code <choose>}·{@code
 * <if>} 로 렌더만 한다.
 *
 * <p>왜: OGNL 식에 정책 규칙(예: hosting null = 외부)을 쓰면 규칙이 XML 과 jOOQ 렌더러 두 곳에 흩어진다. 판단은 여기 한 곳에 두고, jOOQ
 * 렌더러({@code JooqDatasetVisibility})와의 의미 일치는 MyBatisDatasetVisibilityTest 의 행 집합 비교가 고정한다.
 *
 * <p>쓰는 법: 매퍼 메서드가 이 값을 {@code @Param("vis")} 이름으로 받고, 문장에서 {@code <include
 * refid="com.smartfirehub.securitylevel.repository.mybatis.DatasetVisibility.visible">} 에 {@code
 * idCol}· {@code levelCol} 속성(XML 에 박힌 고정 컬럼 식)을 넘긴다.
 */
public final class MyBatisDatasetVisibility {

  private MyBatisDatasetVisibility() {}

  /**
   * 가시성 조각 파라미터 — 조각이 {@code #{vis.x}}·{@code test="vis.x"} 로 읽는다.
   *
   * @param unrestricted 제한 없음 → {@code 1=1}
   * @param noRank 역할 없음 → {@code 1=0}
   * @param rank 자격 rank(등급 rank 이하만 보인다)
   * @param userId 허용 목록 직접 부여 대조용 사용자 id
   * @param roleIds 허용 목록 역할 부여 대조용 역할 id(비어 있으면 역할 IN 절을 렌더하지 않는다 — 빈 {@code IN ()} 은 문법 오류)
   * @param tenantAdmin 시스템 ADMIN 이면 등급의 admin_bypass 로 허용 목록을 우회한다
   * @param ai AI 문맥 여부 — 참이면 ai_policy 술어를 건다
   * @param aiPolicy ai_policy 허용 값(모두 허용·자체 호스팅 허용 — {@code #{vis.aiPolicy.all}}·{@code
   *     #{vis.aiPolicy.selfHosted}})
   * @param share 공유 목적 여부 — 참이면 share_policy 술어를 건다
   * @param sharePolicyAllow share_policy 의 허용 값
   */
  public record VisibilityParams(
      boolean unrestricted,
      boolean noRank,
      int rank,
      long userId,
      List<Long> roleIds,
      boolean tenantAdmin,
      boolean ai,
      AiPolicyValues aiPolicy,
      boolean share,
      String sharePolicyAllow) {}

  /**
   * 등급 ai_policy 허용 값 — {@code aiPolicyAllowedDataset} 조각이 {@code #{aiPolicy.all}}·{@code
   * #{aiPolicy.selfHosted}} 로 읽는다.
   *
   * @param all ai_policy 의 "모두 허용" 값(언제나 허용)
   * @param selfHosted 자체 호스팅이면 함께 허용할 값(SELF_HOSTED_ONLY), 외부(또는 hosting null)면 null
   */
  public record AiPolicyValues(String all, String selfHosted) {}

  /**
   * 공급자 호스팅 위치로 허용할 ai_policy 값을 정한다 — MyBatis 구현이 이 규칙을 쓰는 단 한 곳이다(가시성 조각·임베딩 카탈로그·문서 청크 진행률 분모가
   * 모두 여기서 받는다). 규칙은 {@code JooqDatasetVisibility.aiPolicyAllows} 와 같다: ALL 은 언제나, SELF_HOSTED_ONLY
   * 는 자체 호스팅일 때만, hosting null 은 외부로 취급.
   *
   * <p>묶음 ⑥({@code EmbeddingAiPolicyRepository} 의 MyBatis 이전)이 이 도우미와 {@code
   * aiPolicyAllowedDataset} 조각을 흡수해 한 리포지토리로 옮길 후보다.
   *
   * @param hosting 공급자 호스팅 위치(null 가능)
   * @return 조각에 넘길 허용 값
   */
  public static AiPolicyValues aiPolicyValues(ProviderHosting hosting) {
    return new AiPolicyValues(
        LevelPolicy.AiPolicy.ALL.name(),
        hosting == ProviderHosting.SELF_HOSTED
            ? LevelPolicy.AiPolicy.SELF_HOSTED_ONLY.name()
            : null);
  }

  /**
   * 가시성 값을 조각 파라미터로 펼친다. AI 문맥은 값 생성 시점에 캡처된 것을 그대로 쓴다(렌더 시점 AI 범위는 보지 않는다 — jOOQ 렌더러와 같음).
   *
   * @param v 조회자 가시성
   * @return 조각 파라미터
   */
  public static VisibilityParams params(DatasetVisibility v) {
    String allow = LevelPolicy.SharePolicy.ALLOW.name();
    if (v.unrestricted()) {
      return new VisibilityParams(
          true, false, 0, 0L, List.of(), false, false, aiPolicyValues(null), false, allow);
    }
    Clearance c = v.clearance();
    AiCall ai = v.aiCall();
    // AI 문맥이 없으면 ai_policy 술어를 렌더하지 않으므로 selfHosted 는 null(외부와 같은 값)로 둔다.
    AiPolicyValues aiPolicy = aiPolicyValues(ai == null ? null : ai.hosting());
    return new VisibilityParams(
        false,
        c.rank() == Clearance.NO_RANK,
        c.rank(),
        c.userId(),
        List.copyOf(c.roleIds()),
        c.tenantAdmin(),
        ai != null,
        aiPolicy,
        ai != null && ai.share(),
        allow);
  }
}
