package com.smartfirehub.audit.time;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;

/**
 * 감사 로그 시각(action_time)의 저장 시간대와 경계 변환(WD-11).
 *
 * <p><b>저장 TZ = JVM 기본 TZ 인 이유</b>: action_time 은 {@code TIMESTAMP}(타임존 없음) {@code DEFAULT NOW()}
 * 다. NOW() 는 세션 TimeZone 의 벽시계로 잘려 저장되고, pgjdbc 는 접속 시 JVM 기본 TZ 를 세션 TimeZone 으로 보낸다
 * (application*.yml 에 datasource TimeZone 옵션·init-sql 없음). 실측: 운영 JVM=UTC → KST 00:00 크론 실행이 15:00
 * 으로, dev JVM=Asia/Seoul → DB 서버 TimeZone 이 Etc/UTC 인데도 KST 03:00 크론이 03:00 으로 저장됐다.
 *
 * <p>그래서 저장 TZ 를 아는 것은 서버뿐이다 — 클라이언트는 경계를 절대 시각(오프셋 포함)으로 보내고, 서버가 이 클래스로 저장 벽시계로 바꿔 비교하며, 응답 시각에는
 * 저장 TZ 오프셋을 붙인다. 이 가정은 {@code
 * AuditLogTimeRangeIntegrationTest#responseActionTime_carriesOffset_andIsTheRealInstant} 가 감시한다.
 */
public final class AuditTimes {

  private AuditTimes() {}

  /** 저장 TZ — pgjdbc 세션 TimeZone 과 같은 JVM 기본 TZ. */
  public static ZoneId storageZone() {
    return ZoneId.systemDefault();
  }

  /** 절대 시각을 저장 TZ 벽시계로 바꾼다. */
  public static LocalDateTime toStorage(OffsetDateTime instant, ZoneId storage) {
    return instant.atZoneSameInstant(storage).toLocalDateTime();
  }

  /**
   * 워크스페이스 감사 화면·ai-agent 의 경계 파라미터를 저장 벽시계로 바꾼다.
   *
   * <p>오프셋(Z, +09:00)이 있으면 그 순간을 저장 TZ 로 변환한다. 없으면 저장 TZ 벽시계로 그대로 쓴다 — ai-agent 감사 도구가 오프셋 없는 값을
   * 보내므로 400 회귀를 막는 호환 경로다(현행 동작 유지).
   *
   * @return null/공백이면 null
   * @throws IllegalArgumentException 형식 오류(→ 400)
   */
  public static LocalDateTime parseBoundary(String raw, ZoneId storage) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      TemporalAccessor parsed =
          DateTimeFormatter.ISO_DATE_TIME.parseBest(
              raw.trim(), OffsetDateTime::from, LocalDateTime::from);
      return parsed instanceof OffsetDateTime odt
          ? toStorage(odt, storage)
          : (LocalDateTime) parsed;
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("날짜 형식이 올바르지 않습니다: " + raw);
    }
  }

  /** 응답용 — 저장 벽시계에 저장 TZ 의 오프셋을 붙인다. */
  public static OffsetDateTime fromStorage(LocalDateTime stored, ZoneId storage) {
    return stored.atZone(storage).toOffsetDateTime();
  }
}
