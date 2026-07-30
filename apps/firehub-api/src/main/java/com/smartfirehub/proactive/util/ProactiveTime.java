package com.smartfirehub.proactive.util;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * proactive 모듈의 시각 기준점.
 *
 * <p><b>왜 필요한가</b> — DB의 타임스탬프 컬럼은 {@code timestamp without time zone} 이고, Postgres 기본값(`now()`)으로 채워지는
 * 컬럼(`created_at` 등)은 UTC 벽시계 값이 들어간다. 반면 애플리케이션이 {@code LocalDateTime.now()} 로 쓰던 컬럼은 JVM 기본 존을
 * 따르므로, 로컬 개발(JVM=KST)에서는 KST 값이, 운영 컨테이너(JVM=UTC)에서는 UTC 값이 같은 테이블에 섞여 저장됐다. 프론트엔드 계약은
 * "타임존 없는 문자열 = UTC"({@code formatters.ts parseUtcDate})이므로 저장측을 UTC로 고정해야 환경과 무관하게 일치한다 (#349).
 *
 * <p><b>적용 범위</b> — DB에 기록하거나 DB 값과 비교하는 시각, 그리고 프론트로 나가는 이벤트 타임스탬프에만 쓴다. 리포트 본문에 사람이 읽으라고
 * 찍는 표시용 시각(`generatedAt` 등)은 사용자 타임존 문제라 별개 관심사이며 여기서 다루지 않는다.
 */
public final class ProactiveTime {

  private ProactiveTime() {}

  /** DB 저장·비교용 현재 시각 (UTC 벽시계). JVM 기본 타임존에 영향받지 않는다. */
  public static LocalDateTime nowUtc() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
