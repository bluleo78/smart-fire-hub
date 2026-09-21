package com.smartfirehub.pipeline.service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 스텝 증분 처리 플레이스홀더 {@code {{last_run_at}}} 을 감지·치환한다.
 *
 * <p>값은 엔진이 만든 타임스탬프뿐이라 SQL 주입 위험이 없다. null(첫 실행·전체 재생성)은 {@code -infinity} 로 바꿔 전체를
 * 읽게 한다. 마이크로초를 잃으면 경계 행({@code _updated_at} = 책갈피)을 놓치므로 포맷에서 정밀도를 보존한다.
 *
 * <p><b>경계 규약은 {@code >=} 다.</b> 사용자 SQL 은 반드시 {@code _updated_at >= {{last_run_at}}} 으로 쓴다 —
 * 책갈피와 정확히 같은 시각의 행은 "이미 읽었을 수도, 아직 커밋되지 않았을 수도" 있으므로 다시 읽어야 한다.
 * 중복 읽기는 MERGE 의 멱등성이 흡수하지만, 건너뛴 행은 영원히 복구되지 않는다.
 */
public final class LastRunAtPlaceholder {

  /** 공백을 허용하는 플레이스홀더 패턴 — UI/문서 예시가 {@code {{ last_run_at }}} 로 쓰여도 같게 동작해야 한다. */
  private static final Pattern PATTERN = Pattern.compile("\\{\\{\\s*last_run_at\\s*\\}\\}");

  /** 책갈피가 없을 때(첫 실행·전체 재생성 예약) 쓰는 리터럴 — 모든 행이 이 값보다 크므로 전체를 읽는다. */
  private static final String NEGATIVE_INFINITY = "'-infinity'::timestamptz";

  private LastRunAtPlaceholder() {}

  /** SQL 이 증분 플레이스홀더를 쓰는지 판별한다(증분 스텝 여부 판단의 유일한 기준). */
  public static boolean isUsedIn(String sql) {
    return sql != null && PATTERN.matcher(sql).find();
  }

  /**
   * 플레이스홀더를 타임스탬프 리터럴로 모두 치환한다.
   *
   * @param value 책갈피 값. {@code null} 이면 {@code '-infinity'::timestamptz}(전체 읽기).
   */
  public static String substitute(String sql, OffsetDateTime value) {
    // ISO_OFFSET_DATE_TIME 은 소수 초를 필요한 자리수만큼만 출력한다(.123456 은 그대로) — 값 손실이 없다.
    String literal =
        value == null
            ? NEGATIVE_INFINITY
            : "'" + value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "'::timestamptz";
    return PATTERN.matcher(sql).replaceAll(Matcher.quoteReplacement(literal));
  }
}
