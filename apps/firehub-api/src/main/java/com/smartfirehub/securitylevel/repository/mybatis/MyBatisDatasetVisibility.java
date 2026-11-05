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
   * @param aiPolicyAll ai_policy 의 "모두 허용" 값
   * @param aiPolicySelfHosted 자체 호스팅이면 함께 허용할 ai_policy 값, 외부(또는 hosting null)면 null
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
      String aiPolicyAll,
      String aiPolicySelfHosted,
      boolean share,
      String sharePolicyAllow) {}

  /**
   * 가시성 값을 조각 파라미터로 펼친다. AI 문맥은 값 생성 시점에 캡처된 것을 그대로 쓴다(렌더 시점 AI 범위는 보지 않는다 — jOOQ 렌더러와 같음).
   *
   * @param v 조회자 가시성
   * @return 조각 파라미터
   */
  public static VisibilityParams params(DatasetVisibility v) {
    String all = LevelPolicy.AiPolicy.ALL.name();
    String allow = LevelPolicy.SharePolicy.ALLOW.name();
    if (v.unrestricted()) {
      return new VisibilityParams(
          true, false, 0, 0L, List.of(), false, false, all, null, false, allow);
    }
    Clearance c = v.clearance();
    AiCall ai = v.aiCall();
    // hosting null 은 외부로 취급한다(JooqDatasetVisibility.aiPolicyAllows 와 같은 규칙).
    String selfHosted =
        ai != null && ai.hosting() == ProviderHosting.SELF_HOSTED
            ? LevelPolicy.AiPolicy.SELF_HOSTED_ONLY.name()
            : null;
    return new VisibilityParams(
        false,
        c.rank() == Clearance.NO_RANK,
        c.rank(),
        c.userId(),
        List.copyOf(c.roleIds()),
        c.tenantAdmin(),
        ai != null,
        all,
        selfHosted,
        ai != null && ai.share(),
        allow);
  }
}
