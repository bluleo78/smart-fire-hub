package com.smartfirehub.pipeline.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Tibero 증분 시각을 문자열로 주고받을 때 쓰는 공통 조각(WD-77) — 증분 책갈피 후보 조회({@link TiberoIncrementalCursor})와 플레이스홀더
 * 치환({@link LastRunAtPlaceholder})이 같은 형식·같은 서버 시간대 식을 쓰도록 한 곳에 둔다. 둘이 어긋나면 후보로 읽은 시각과 다음 실행이 넣는 경계
 * 시각의 정밀도·시간대가 달라진다.
 */
public final class TiberoTimestamps {

  private TiberoTimestamps() {}

  /**
   * UTC 시각 문자열 형식 {@code yyyy-MM-dd HH:mm:ss.SSSSSS} — 마이크로초 6자리 고정(Tibero 시계 정밀도). Tibero 쪽 {@code
   * 'YYYY-MM-DD HH24:MI:SS.FF6'} 와 같은 모양이다.
   */
  static final DateTimeFormatter UTC_MICROS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

  /**
   * Tibero 서버의 지역 시간대({@code TO_CHAR(SYSTIMESTAMP, 'TZR')})를 내는 SQL 식(WD-234). 서버 벽시계와 UTC 를 오갈 때
   * 쓴다.
   *
   * <p>서버가 지역 이름({@code Etc/Universal}·{@code America/New_York} 등)을 주면 {@code FROM_TZ}·{@code AT
   * TIME ZONE} 이 <b>그 시각의</b> 오프셋을 써서 서머타임 전환을 가로질러도 맞다. 오프셋 문자열({@code +09:00})을 주는 서버에서는 예전 「현재
   * 오프셋」 식과 같다(둘 다 실서버 확인). 예전 현재 오프셋({@code TZH:TZM}) 식은 지난 실행과 이번 실행 사이에 전환이 끼면 1시간 어긋났다. 컴파일 상수라
   * 이 식을 이어 붙인 SQL 상수도 바이트 그대로다.
   */
  static final String SERVER_ZONE_SQL = "TO_CHAR(SYSTIMESTAMP, 'TZR')";

  /**
   * UTC 순간을 Tibero 서버 벽시계 TIMESTAMP 로 바꾸는 SQL 리터럴 식(WD-234 Ruling 13). {@code _UPDATED_AT} 은 서버 시계
   * ({@code SYSTIMESTAMP})의 벽시계 값이라, UTC 로 저장한 책갈피와 비교하려면 서버 지역 시간대로 옮긴 뒤 시간대를 떼야 한다. 파이프라인 플레이스홀더
   * ({@link LastRunAtPlaceholder})와 행 검색 증분 읽기(WD-723)가 같은 식을 쓴다 — 실서버로 확인된 식을 한 곳에만 둔다.
   *
   * <p>값은 {@link OffsetDateTime} 을 고정 형식으로 찍은 것이라 사용자 입력이 섞이지 않는다(리터럴이어도 주입 경로가 없다).
   */
  public static String serverWallClockLiteral(OffsetDateTime value) {
    return "CAST(TIMESTAMP '"
        + value.withOffsetSameInstant(ZoneOffset.UTC).format(UTC_MICROS)
        + " +00:00' AT TIME ZONE "
        + SERVER_ZONE_SQL
        + " AS TIMESTAMP)";
  }
}
