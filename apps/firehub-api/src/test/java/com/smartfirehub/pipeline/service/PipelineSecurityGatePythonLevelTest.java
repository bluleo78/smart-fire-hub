package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 공통 결정 R4 — PYTHON 출력 등급 계산 규칙({@link PipelineSecurityGate#pythonReadableTopLevel}): 실행 주체 자격
 * 이하이면서 허용 목록 필요가 아닌 등급 중 최고. DB 없이 규칙만 고정한다(흐름 C 의 슬롯 위치 계산과 같아야 하는 규칙이라 경계를 명시적으로 남긴다).
 */
class PipelineSecurityGatePythonLevelTest {

  private static LevelPolicy level(long id, String name, int rank, boolean allowlist) {
    return new LevelPolicy(
        id,
        name,
        rank,
        false,
        allowlist,
        false,
        LevelPolicy.ExportPolicy.ALLOW,
        LevelPolicy.AiPolicy.ALL,
        LevelPolicy.SharePolicy.ALLOW,
        false);
  }

  /** V133 기본 4등급(기밀만 허용 목록 필요). */
  private static final List<LevelPolicy> DEFAULTS =
      List.of(
          level(1, "공개", 1, false),
          level(2, "내부", 2, false),
          level(3, "민감", 3, false),
          level(4, "기밀", 4, true));

  /** ADMIN(최상위 '기밀' 자격) — '기밀'은 허용 목록 등급이라 PYTHON 이 못 읽으므로 출력은 '민감'. */
  @Test
  void topClearance_excludesAllowlistLevel() {
    assertThat(PipelineSecurityGate.pythonReadableTopLevel(DEFAULTS, 4))
        .map(LevelPolicy::name)
        .hasValue("민감");
  }

  /** 일반 케이스 — '민감' 자격 실행 주체 → '민감'. */
  @Test
  void ordinaryClearance_isItsOwnLevel() {
    assertThat(PipelineSecurityGate.pythonReadableTopLevel(DEFAULTS, 3))
        .map(LevelPolicy::name)
        .hasValue("민감");
  }

  /**
   * 범위가 비면(자격 이하 등급이 전부 허용 목록 필요, 또는 역할 없음) empty — 게이트는 이를 "전파할 입력 없음"으로 보고 출력 VIEW 만 본다(원장
   * Ruling). 아무 등급이나 조용히 고르지 않는다.
   */
  @Test
  void emptyRange_isEmpty() {
    List<LevelPolicy> allowlistBottom =
        List.of(level(1, "비밀1", 1, true), level(2, "비밀2", 2, true), level(3, "공개", 3, false));
    assertThat(PipelineSecurityGate.pythonReadableTopLevel(allowlistBottom, 2)).isEmpty();
    assertThat(PipelineSecurityGate.pythonReadableTopLevel(DEFAULTS, Clearance.NO_RANK)).isEmpty();
  }
}
