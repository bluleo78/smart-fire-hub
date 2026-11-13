package com.smartfirehub.global.platformdb.mybatis;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * 「내 비공유 자산만 공유로 바꾸고 바뀐 것의 id → 이름을 돌려준다」 공용 흐름(WD-710).
 *
 * <p>왜 있는가: jOOQ 판은 한 문장 {@code UPDATE … SET is_shared = true … RETURNING id, name} 이지만 Tibero 에는
 * RETURNING 이 없어, MyBatis 판은 대상을 먼저 FOR UPDATE 로 잠가 읽고(lockTargets) 그 id 만 UPDATE(markShared) 하는 두
 * 문장으로 옮겼다. 차트·저장 쿼리가 같은 흐름을 복사하고 있어 한 곳으로 모았다. 두 문장이 같은 원자성을 가지려면 한 트랜잭션이어야 하므로 호출하는 리포지토리 메서드에
 * {@code @Transactional} 을 둔다(이 헬퍼는 트랜잭션을 열지 않는다).
 *
 * <p>잠근 행만 바꾸므로 잠근 수와 바꾼 수는 같아야 한다 — 다르면 감사용 맵이 실제와 어긋나므로 조용히 넘기지 않고 {@link IllegalStateException}
 * 으로 알린다.
 */
public final class PlatformSharing {

  private PlatformSharing() {}

  /** 잠근 공유 대상 행 — id 와 이름만 쓴다(도메인 행 레코드가 구현한다). */
  public interface Target {
    Long id();

    String name();
  }

  /**
   * 공유 켜기 흐름을 실행한다.
   *
   * @param ids 요청 id 목록 — 비면 문장을 돌지 않고 빈 맵
   * @param lockTargets id 조각을 받아 대상(내가 만든 비공유)을 잠그고 읽는 문장
   * @param markShared 잠근 id 조각을 공유로 바꾸고 바꾼 행 수를 돌려주는 문장
   * @param label 수 불일치 예외 메시지 머리말(예: "차트")
   * @return 바뀐 행의 id → 이름(id 오름차순)
   */
  public static Map<Long, String> flip(
      Collection<Long> ids,
      Function<List<List<Long>>, List<? extends Target>> lockTargets,
      ToIntFunction<List<List<Long>>> markShared,
      String label) {
    if (ids.isEmpty()) return Map.of();
    Map<Long, String> flipped = new TreeMap<>();
    lockTargets.apply(InChunks.of(ids)).forEach(t -> flipped.put(t.id(), t.name()));
    if (flipped.isEmpty()) return flipped;
    int updated = markShared.applyAsInt(InChunks.of(flipped.keySet()));
    if (updated != flipped.size()) {
      throw new IllegalStateException(
          label + " 공유 갱신 행 수가 잠근 대상 수와 다르다: locked=" + flipped.size() + ", updated=" + updated);
    }
    return flipped;
  }
}
