package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 슬롯 위치 계산(순수 함수, DB 없음) — 스펙 §4.1·공통 결정 R4 의 규칙을 고정한다. */
class PythonReadSlotsTest {

  private static LevelPolicy level(long id, int rank, boolean allowlist) {
    return new LevelPolicy(
        id,
        "L" + id,
        rank,
        false,
        allowlist,
        false,
        LevelPolicy.ExportPolicy.ALLOW,
        LevelPolicy.AiPolicy.ALL,
        LevelPolicy.SharePolicy.ALLOW,
        false);
  }

  /** V133 기본 시드 모양: 공개·내부·민감·기밀(허용 목록). */
  private static final List<LevelPolicy> DEFAULT4 =
      List.of(level(11, 1, false), level(12, 2, false), level(13, 3, false), level(14, 4, true));

  @Test
  void slotIsPositionOfClearanceRank() {
    assertThat(PythonReadSlots.slotFor(DEFAULT4, 2)).isEqualTo(2);
    assertThat(PythonReadSlots.slotFor(DEFAULT4, 4)).isEqualTo(4);
    assertThat(PythonReadSlots.slotFor(DEFAULT4, 0)).as("가장 낮은 등급보다 낮은 자격").isZero();
    assertThat(PythonReadSlots.slotFor(DEFAULT4, Clearance.NO_RANK)).isZero();
  }

  /** rank 공백(삭제 후)에서도 값이 아니라 위치로 센다. */
  @Test
  void positionsIgnoreRankGaps() {
    List<LevelPolicy> gaps = List.of(level(1, 1, false), level(2, 3, false), level(3, 7, false));
    assertThat(PythonReadSlots.slotFor(gaps, 7)).isEqualTo(3);
    assertThat(PythonReadSlots.slotFor(gaps, 3)).isEqualTo(2);
    assertThat(PythonReadSlots.slotFor(gaps, 5)).as("공백 사이 자격은 아래 등급 위치").isEqualTo(2);
    assertThat(PythonReadSlots.grantSlotsFor(gaps, 2L)).containsExactly(2, 3, 4, 5, 6, 7, 8, 9, 10);
  }

  @Test
  void grantSlotsAreFromPositionToTen_allowlistNone() {
    assertThat(PythonReadSlots.grantSlotsFor(DEFAULT4, 11L)).hasSize(10).contains(1, 10);
    assertThat(PythonReadSlots.grantSlotsFor(DEFAULT4, 13L))
        .containsExactly(3, 4, 5, 6, 7, 8, 9, 10);
    assertThat(PythonReadSlots.grantSlotsFor(DEFAULT4, 14L)).as("허용 목록 등급은 어느 슬롯에도 없다").isEmpty();
    assertThat(PythonReadSlots.grantSlotsFor(DEFAULT4, 999L)).as("모르는 등급은 fail-closed").isEmpty();
    assertThat(PythonReadSlots.grantSlotsFor(DEFAULT4, null)).as("등급 미지정은 fail-closed").isEmpty();
  }

  /** 경합으로 11번째 등급이 생겨도 그 위치는 GRANT 대상이 없고 슬롯도 11(범위 밖)이다. */
  @Test
  void beyondTenSlotsHasNoGrantAndNoSlot() {
    List<LevelPolicy> eleven = new ArrayList<>();
    for (int i = 1; i <= 11; i++) {
      eleven.add(level(i, i, false));
    }
    assertThat(PythonReadSlots.grantSlotsFor(eleven, 11L)).isEmpty();
    assertThat(PythonReadSlots.grantSlotsFor(eleven, 10L)).containsExactly(10);
    assertThat(PythonReadSlots.slotFor(eleven, 11)).isEqualTo(11);
    assertThat(PythonReadSlots.readableLevels(eleven, 11)).as("슬롯 범위 밖은 읽을 등급 없음").isEmpty();
  }

  /**
   * R4: 기밀(허용 목록) 자격 실행 주체가 읽을 수 있는 최고 등급은 민감이다 — PYTHON 출력 등급의 기준. 흐름 B 의 출력 등급 계산과 일치 테스트를 붙일 기준
   * 함수.
   */
  @Test
  void readableLevels_excludeAllowlist_andStopAtClearance() {
    assertThat(PythonReadSlots.readableLevels(DEFAULT4, 4))
        .extracting(LevelPolicy::id)
        .containsExactly(11L, 12L, 13L);
    assertThat(PythonReadSlots.readableLevels(DEFAULT4, 2))
        .extracting(LevelPolicy::id)
        .containsExactly(11L, 12L);
    assertThat(PythonReadSlots.readableLevels(DEFAULT4, Clearance.NO_RANK)).isEmpty();
    // 허용 목록 등급이 중간에 있어도 그 위로는 계속 읽는다(위치 규칙).
    List<LevelPolicy> mid = List.of(level(1, 1, false), level(2, 2, true), level(3, 3, false));
    assertThat(PythonReadSlots.readableLevels(mid, 3))
        .extracting(LevelPolicy::id)
        .containsExactly(1L, 3L);
  }
}
