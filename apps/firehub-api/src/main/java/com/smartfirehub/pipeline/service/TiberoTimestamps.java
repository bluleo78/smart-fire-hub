package com.smartfirehub.pipeline.service;

import java.time.format.DateTimeFormatter;

/**
 * Tibero 증분 시각을 문자열로 주고받을 때 쓰는 공통 조각(WD-77) — 증분 책갈피 후보 조회({@link TiberoIncrementalCursor})와 플레이스홀더
 * 치환({@link LastRunAtPlaceholder})이 같은 형식·같은 서버 오프셋 식을 쓰도록 한 곳에 둔다. 둘이 어긋나면 후보로 읽은 시각과 다음 실행이 넣는 경계
 * 시각의 정밀도·시간대가 달라진다.
 */
final class TiberoTimestamps {

  private TiberoTimestamps() {}

  /**
   * UTC 시각 문자열 형식 {@code yyyy-MM-dd HH:mm:ss.SSSSSS} — 마이크로초 6자리 고정(Tibero 시계 정밀도). Tibero 쪽 {@code
   * 'YYYY-MM-DD HH24:MI:SS.FF6'} 와 같은 모양이다.
   */
  static final DateTimeFormatter UTC_MICROS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

  /**
   * Tibero 서버의 현재 UTC 오프셋({@code +09:00} 모양)을 내는 SQL 식. 서버 시간대를 가정하지 않고 서버 벽시계와 UTC 를 오갈 때 쓴다. 컴파일
   * 상수라 이 식을 이어 붙인 SQL 상수도 바이트 그대로다.
   */
  static final String SERVER_OFFSET_SQL = "TO_CHAR(SYSTIMESTAMP, 'TZH:TZM')";
}
