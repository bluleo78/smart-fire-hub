package com.smartfirehub.securitylevel.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.securitylevel.access.LevelPolicy.AiPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.ExportPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.SharePolicy;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 스펙 §2.5 판정 규칙의 조합 매트릭스. Spring 없이 도는 순수 테스트 — 규칙 변경이 여기서 먼저 드러나야 한다. */
class DatasetAccessPolicyTest {

  private static LevelPolicy level(
      int rank, boolean allowlist, boolean bypass, ExportPolicy e, AiPolicy a, SharePolicy s) {
    return new LevelPolicy(100L + rank, "L" + rank, rank, false, allowlist, bypass, e, a, s, false);
  }

  private static final LevelPolicy INTERNAL =
      level(2, false, false, ExportPolicy.ALLOW, AiPolicy.ALL, SharePolicy.ALLOW);
  private static final LevelPolicy SENSITIVE =
      level(3, false, false, ExportPolicy.PERMISSION, AiPolicy.SELF_HOSTED_ONLY, SharePolicy.ALLOW);
  private static final LevelPolicy SECRET =
      level(4, true, false, ExportPolicy.DENY, AiPolicy.SELF_HOSTED_ONLY, SharePolicy.DENY);
  private static final LevelPolicy SECRET_BYPASS =
      level(4, true, true, ExportPolicy.DENY, AiPolicy.SELF_HOSTED_ONLY, SharePolicy.DENY);

  private static Decision decide(
      int userRank, boolean onList, boolean admin, LevelPolicy l, DatasetAction act) {
    return DatasetAccessPolicy.decide(
        new AccessInput(userRank, onList, admin, l, act, Set.of(), ProviderHosting.EXTERNAL));
  }

  static Stream<Arguments> viewMatrix() {
    // userRank, onAllowlist, tenantAdmin, level, expectedAllowed, expectedReason
    return Stream.of(
        Arguments.of(2, false, false, INTERNAL, true, null),
        Arguments.of(1, false, false, INTERNAL, false, "CLEARANCE_INSUFFICIENT"),
        Arguments.of(3, false, false, INTERNAL, true, null),
        Arguments.of(4, false, false, SECRET, false, "NOT_ON_ALLOWLIST"),
        Arguments.of(4, true, false, SECRET, true, null),
        Arguments.of(3, true, false, SECRET, false, "CLEARANCE_INSUFFICIENT"),
        // ADMIN 이라도 우회가 꺼져 있으면 허용 목록이 필요하다(스펙 §2.2 기본값 ✗).
        Arguments.of(4, false, true, SECRET, false, "NOT_ON_ALLOWLIST"),
        Arguments.of(4, false, true, SECRET_BYPASS, true, null),
        // 우회가 켜져 있어도 ADMIN 이 아니면 소용없다.
        Arguments.of(4, false, false, SECRET_BYPASS, false, "NOT_ON_ALLOWLIST"),
        // 우회는 rank 를 건너뛰지 않는다.
        Arguments.of(3, false, true, SECRET_BYPASS, false, "CLEARANCE_INSUFFICIENT"),
        // 자격 없음(역할 0개) — fail-closed.
        Arguments.of(Clearance.NO_RANK, false, false, INTERNAL, false, "CLEARANCE_INSUFFICIENT"));
  }

  @ParameterizedTest
  @MethodSource("viewMatrix")
  void view(
      int rank, boolean onList, boolean admin, LevelPolicy l, boolean allowed, String reason) {
    Decision d = decide(rank, onList, admin, l, DatasetAction.VIEW);
    assertThat(d.allowed()).isEqualTo(allowed);
    assertThat(d.reasonCode()).isEqualTo(reason);
    assertThat(d.levelId()).isEqualTo(l.id());
  }

  @Test
  void canView_matchesDecideView() {
    assertThat(DatasetAccessPolicy.canView(4, false, true, SECRET_BYPASS)).isTrue();
  }

  @Test
  void export_followsPolicy_afterView() {
    assertThat(decide(2, false, false, INTERNAL, DatasetAction.EXPORT).allowed()).isTrue();
    Decision needPerm = decide(3, false, false, SENSITIVE, DatasetAction.EXPORT);
    assertThat(needPerm.allowed()).isFalse();
    assertThat(needPerm.reasonCode()).isEqualTo("EXPORT_PERMISSION_REQUIRED");
    assertThat(needPerm.policyKey()).isEqualTo("export_policy");
    Decision withPerm =
        DatasetAccessPolicy.decide(
            new AccessInput(
                3,
                false,
                false,
                SENSITIVE,
                DatasetAction.EXPORT,
                Set.of(DatasetAccessPolicy.EXPORT_RESTRICTED_PERMISSION),
                null));
    assertThat(withPerm.allowed()).isTrue();
    assertThat(decide(4, true, false, SECRET, DatasetAction.EXPORT).reasonCode())
        .isEqualTo("EXPORT_DENIED");
    // VIEW 가 먼저다 — 볼 수 없으면 내보내기 사유가 아니라 열람 사유가 나온다.
    assertThat(decide(1, false, false, INTERNAL, DatasetAction.EXPORT).reasonCode())
        .isEqualTo("CLEARANCE_INSUFFICIENT");
  }

  @Test
  void ai_respectsHosting_andNullHostingIsExternal() {
    assertThat(decide(3, false, false, SENSITIVE, DatasetAction.AI).reasonCode())
        .isEqualTo("AI_EXTERNAL_DENIED");
    Decision selfHosted =
        DatasetAccessPolicy.decide(
            new AccessInput(
                3,
                false,
                false,
                SENSITIVE,
                DatasetAction.AI,
                Set.of(),
                ProviderHosting.SELF_HOSTED));
    assertThat(selfHosted.allowed()).isTrue();
    Decision nullHosting =
        DatasetAccessPolicy.decide(
            new AccessInput(3, false, false, SENSITIVE, DatasetAction.AI, Set.of(), null));
    assertThat(nullHosting.allowed()).as("hosting 미상은 외부로 간주(fail-closed)").isFalse();
    LevelPolicy aiDeny =
        level(2, false, false, ExportPolicy.ALLOW, AiPolicy.DENY, SharePolicy.ALLOW);
    assertThat(decide(2, false, false, aiDeny, DatasetAction.AI).reasonCode())
        .isEqualTo("AI_DENIED");
  }

  @Test
  void share_followsPolicy() {
    assertThat(decide(2, false, false, INTERNAL, DatasetAction.SHARE).allowed()).isTrue();
    Decision d = decide(4, true, false, SECRET, DatasetAction.SHARE);
    assertThat(d.reasonCode()).isEqualTo("SHARE_DENIED");
    assertThat(d.policyKey()).isEqualTo("share_policy");
  }

  @Test
  void unknownLevel_isDenied() {
    Decision d =
        DatasetAccessPolicy.decide(
            new AccessInput(4, true, true, null, DatasetAction.VIEW, Set.of(), null));
    assertThat(d.allowed()).isFalse();
    assertThat(d.reasonCode()).isEqualTo("LEVEL_UNKNOWN");
  }

  @Test
  void restricted_derivedFromPolicies() {
    assertThat(INTERNAL.restricted()).isFalse();
    assertThat(SENSITIVE.restricted()).isTrue();
  }

  /** S3: 사용자 없는 등급 판정(aiAllowedForLevel)이 decide(AI) 와 모든 정책×호스팅에서 같은 답을 낸다. */
  @Test
  void aiAllowedForLevel_agreesWithDecideAi_forEveryPolicyAndHosting() {
    for (AiPolicy p : AiPolicy.values()) {
      for (ProviderHosting h : ProviderHosting.values()) {
        LevelPolicy lv =
            new LevelPolicy(
                1L, "L", 1, false, false, false, ExportPolicy.ALLOW, p, SharePolicy.ALLOW, false);
        boolean decided =
            DatasetAccessPolicy.decide(
                    new AccessInput(1, false, false, lv, DatasetAction.AI, Set.of(), h))
                .allowed();
        assertThat(DatasetAccessPolicy.aiAllowedForLevel(lv, h))
            .as("%s/%s", p, h)
            .isEqualTo(decided);
      }
    }
  }

  /** S3: 사용자 없는 공유 판정(shareAllowedForLevel)이 decide(SHARE) 와 같은 답을 낸다. */
  @Test
  void shareAllowedForLevel_agreesWithDecideShare() {
    for (SharePolicy s : SharePolicy.values()) {
      LevelPolicy lv =
          new LevelPolicy(
              1L, "L", 1, false, false, false, ExportPolicy.ALLOW, AiPolicy.ALL, s, false);
      boolean decided =
          DatasetAccessPolicy.decide(
                  new AccessInput(1, false, false, lv, DatasetAction.SHARE, Set.of(), null))
              .allowed();
      assertThat(DatasetAccessPolicy.shareAllowedForLevel(lv)).as("%s", s).isEqualTo(decided);
    }
  }
}
