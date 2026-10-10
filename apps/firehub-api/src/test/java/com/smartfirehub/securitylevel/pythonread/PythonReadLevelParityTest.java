package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.service.PipelineSecurityGate;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 공통 결정 R4 일치 — PYTHON 출력 등급(흐름 B, {@link PipelineSecurityGate#pythonReadableTopLevel})과 PYTHON 슬롯
 * 롤이 실제로 읽는 범위의 최고 등급(흐름 C, {@link PythonReadSlots#readableLevels} 의 마지막)이 같은 입력에서 같아야 한다.
 *
 * <p>왜 필요한가: 두 계산이 어긋나면 출력 등급이 실제 읽은 데이터보다 낮게(과소 등급 = 유출) 또는 높게(과잉 분류) 매겨진다. 두 함수는 서로 독립 구현이라(B 는
 * rank 필터·최대, C 는 슬롯 위치·GRANT 집합) 규칙 한쪽만 바뀌는 회귀를 이 테스트가 잡는다. B 쪽에는 일부러 rank 순서가 섞인 컬렉션을 넘겨 정렬 가정
 * 차이도 드러낸다(운영 호출부는 rank 오름차순 findAll 을 넘긴다).
 */
class PythonReadLevelParityTest {

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

  /** rank 목록과 허용 목록 등급 rank 들로 등급 집합을 만든다(id = 100 + rank). */
  private static List<LevelPolicy> levels(int[] ranks, int... allowlistRanks) {
    List<LevelPolicy> out = new ArrayList<>();
    for (int r : ranks) {
      boolean allow = IntStream.of(allowlistRanks).anyMatch(a -> a == r);
      out.add(level(100 + r, r, allow));
    }
    return out;
  }

  private static int[] range(int n) {
    return IntStream.rangeClosed(1, n).toArray();
  }

  static Stream<Arguments> cases() {
    List<Arguments> cases = new ArrayList<>();
    // V133 기본 4등급(기밀=허용 목록) — 각 자격, 최하위보다 낮은 자격, 역할 없음(NO_RANK).
    int[] defaults = range(4);
    for (int c : new int[] {0, 1, 2, 3, 4, Clearance.NO_RANK}) {
      cases.add(Arguments.of("기본4/자격" + c, levels(defaults, 4), c));
    }
    // 비연속 rank(등급 삭제 후) — 등급 사이·위·정확히 일치하는 자격.
    int[] gaps = {1, 3, 7, 9};
    for (int c : new int[] {2, 3, 6, 8, 9, 100}) {
      cases.add(Arguments.of("비연속/자격" + c, levels(gaps), c));
      cases.add(Arguments.of("비연속+허용목록7/자격" + c, levels(gaps, 7), c));
    }
    // 허용 목록 등급이 중간·최하위·전부.
    for (int c : range(5)) {
      cases.add(Arguments.of("중간허용목록3/자격" + c, levels(range(5), 3), c));
      cases.add(Arguments.of("최하위허용목록/자격" + c, levels(range(5), 1, 2), c));
      cases.add(Arguments.of("전부허용목록/자격" + c, levels(range(5), 1, 2, 3, 4, 5), c));
    }
    // 실행 주체 최고 자격 등급 자체가 허용 목록(ADMIN '기밀' 모양) — 비연속 rank 와 함께.
    cases.add(Arguments.of("자격=허용목록/비연속", levels(new int[] {2, 5, 8}, 8), 8));
    // 상한 경계 — 10개(전 위치 슬롯 존재)와 11개(경합으로 생긴 11번째, 슬롯 없음).
    for (int c : new int[] {1, 5, 9, 10}) {
      cases.add(Arguments.of("10개/자격" + c, levels(range(10), 10), c));
    }
    for (int c : new int[] {1, 5, 10, 11, 12}) {
      cases.add(Arguments.of("11개/자격" + c, levels(range(11)), c));
      cases.add(Arguments.of("11개+허용목록11/자격" + c, levels(range(11), 11), c));
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void pythonOutputLevel_equalsTopSlotReadableLevel(
      String label, List<LevelPolicy> levels, int clearanceRank) {
    List<LevelPolicy> asc =
        levels.stream().sorted(Comparator.comparingInt(LevelPolicy::rank)).toList();
    List<LevelPolicy> shuffled = new ArrayList<>(levels);
    Collections.shuffle(shuffled, new Random(label.hashCode()));

    Optional<Long> fromGate =
        PipelineSecurityGate.pythonReadableTopLevel(shuffled, clearanceRank).map(LevelPolicy::id);
    List<LevelPolicy> readable = PythonReadSlots.readableLevels(asc, clearanceRank);
    Optional<Long> fromSlots =
        readable.isEmpty() ? Optional.empty() : Optional.of(readable.get(readable.size() - 1).id());

    assertThat(fromGate).as(label).isEqualTo(fromSlots);
  }
}
