package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * PYTHON 읽기 슬롯 위치 계산(순수 함수, 스펙 §4.1 · 공통 결정 R4). 슬롯 k = "rank 오름차순 k 번째까지의 등급"을 읽는 롤.
 *
 * <p><b>왜 rank 값이 아니라 위치인가.</b> 등급 삭제 후 rank 는 비연속(1,3,7)이 될 수 있다. 롤은 10개로 고정이라 rank 값에 묶으면 공백 때문에
 * 슬롯이 모자라거나 엉뚱한 슬롯을 가리킨다. 그래서 오름차순 목록에서의 1-based 위치로 센다.
 *
 * <p><b>왜 이 한 곳에 모으는가.</b> GRANT 계산(PythonReadGrantSync)과 PYTHON 출력 등급 계산(흐름 B 의
 * PipelineSecurityGate)이 같은 규칙을 써야 한다(R4). 두 계산의 일치 테스트가 비교할 기준 함수가 {@link #readableLevels} 다.
 */
public final class PythonReadSlots {

  private PythonReadSlots() {}

  /**
   * 실행 주체 자격 rank 이하인 등급 수 = 실행 주체의 슬롯 k. 0 이면 슬롯 없음(NO_RANK 이거나 가장 낮은 등급보다도 낮음). 10 초과는 그대로 돌려준다 —
   * 범위 밖 거부는 호출부(prepareForRun) 몫이다.
   *
   * @param levelsAsc 테넌트 등급 전부(rank 오름차순)
   */
  public static int slotFor(List<LevelPolicy> levelsAsc, int clearanceRank) {
    if (clearanceRank == Clearance.NO_RANK) {
      return 0;
    }
    return (int) levelsAsc.stream().filter(l -> l.rank() <= clearanceRank).count();
  }

  /**
   * 등급 levelId 인 데이터셋 테이블에 SELECT 를 줄 슬롯 집합 — 위치 p 면 p..10.
   *
   * <p>허용 목록 등급은 사용자 단위 허용이라 롤(위치 단위)로 표현할 수 없어 비운다(보수적, 스펙 §4.1). 모르는 등급·null·10 초과 위치(등급 상한 경합)도
   * 비운다(fail-closed — 과소권한은 permission denied 로 드러날 뿐 유출이 아니다).
   */
  public static Set<Integer> grantSlotsFor(List<LevelPolicy> levelsAsc, Long levelId) {
    if (levelId == null) {
      return Set.of();
    }
    for (int i = 0; i < levelsAsc.size(); i++) {
      LevelPolicy l = levelsAsc.get(i);
      if (l.id() == levelId) {
        int p = i + 1;
        if (l.allowlistRequired() || p > TenantPipelineRole.PYTHON_READ_SLOTS) {
          return Set.of();
        }
        Set<Integer> slots = new TreeSet<>();
        for (int k = p; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
          slots.add(k);
        }
        return slots;
      }
    }
    return Set.of();
  }

  /**
   * 자격 rank 의 실행 주체가 PYTHON 슬롯 롤로 실제 읽을 수 있는 등급들(rank 오름차순). R4 의 PYTHON 출력 등급 = 이 목록의 마지막(최고) 등급.
   *
   * <p>독자 규칙을 새로 쓰지 않고 {@link #slotFor}·{@link #grantSlotsFor} 로 정의한다 — 그래야 GRANT 계산과 출력 등급 계산이 갈라질
   * 수 없다. 슬롯이 범위(1~10) 밖이면 빈 목록(실행 자체가 거부되는 경우).
   */
  public static List<LevelPolicy> readableLevels(List<LevelPolicy> levelsAsc, int clearanceRank) {
    int k = slotFor(levelsAsc, clearanceRank);
    if (k < 1 || k > TenantPipelineRole.PYTHON_READ_SLOTS) {
      return List.of();
    }
    return levelsAsc.stream().filter(l -> grantSlotsFor(levelsAsc, l.id()).contains(k)).toList();
  }
}
