package com.smartfirehub.pipeline.dto;

import java.util.Locale;
import java.util.Optional;

public enum LoadStrategy {
  REPLACE, // Current default: TRUNCATE + INSERT
  APPEND, // INSERT without truncation
  // MERGE: 출력 데이터셋 PK 기준 upsert. SQL 스텝 전용
  MERGE;

  /**
   * 저장된 문자열을 전략으로 해석한다. 해석 불가(null·오타·과거 값)면 빈 Optional 이다.
   *
   * <p>{@code load_strategy} 컬럼에는 서버 쪽 enum·체크 제약이 없어 임의 문자열이 그대로 들어올 수 있으므로, 읽는 쪽은 항상 "해석 실패"를 다룰 수
   * 있어야 한다. 폴백 정책(REPLACE 로 볼지, 아무것도 안 할지)은 호출자마다 다르므로 여기서 정하지 않는다.
   *
   * <p><b>trim 하지 않는다.</b> 기존 실행 경로는 {@code equalsIgnoreCase} 로만 판단해 {@code " APPEND"} 같은 공백 포함 값을
   * "알 수 없는 값"으로 취급해 왔다 — 여기서 공백을 떼면 그 해석이 조용히 바뀐다.
   */
  public static Optional<LoadStrategy> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(valueOf(raw.toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
