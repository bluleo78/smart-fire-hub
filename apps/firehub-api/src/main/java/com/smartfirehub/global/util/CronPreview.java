package com.smartfirehub.global.util;

import com.smartfirehub.global.dto.CronPreviewResponse;
import com.smartfirehub.proactive.util.ProactiveCron;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.scheduling.support.CronExpression;

/**
 * 편집 창 cron 미리보기(「다음 5회」·「다음 실행」) 계산 (WD-391).
 *
 * <p>예전에는 웹이 cron-parser 로 따로 계산해, 서머타임 전환일에 스케줄러(Spring CronTrigger)의 실제 실행과 달랐다. 이제 스케줄러와 같은
 * {@link DstAwareCron} 으로 서버에서 계산해 돌려준다. 파이프라인 트리거와 스마트 작업 미리보기 엔드포인트가 함께 쓴다.
 */
public final class CronPreview {

  /** 기본 미리보기 개수(트리거 편집 창 「다음 5회」). */
  public static final int DEFAULT_COUNT = 5;

  /** 한 번에 계산할 최대 개수 — 화면 용도라 이 이상은 필요 없다. */
  public static final int MAX_COUNT = 10;

  /** 시간대를 비워 보내면 쓰는 값 — 트리거·스마트 작업 저장 기본값과 같다. */
  public static final String DEFAULT_TIMEZONE = ProactiveCron.DEFAULT_TIMEZONE;

  private CronPreview() {}

  /**
   * {@code from} 이후 발화 시각을 계산한다.
   *
   * <p>잘못된 입력은 저장 검증과 같은 한국어 문구의 {@link IllegalArgumentException}(→ 400)으로 알린다 — 형식 오류, 1분보다 잦은
   * 주기(WD-366), IANA 가 아닌 시간대, 범위 밖 개수.
   *
   * @param cron 5필드/6필드/매크로 cron 식
   * @param timezone IANA 시간대. 비면 {@link #DEFAULT_TIMEZONE}
   * @param count 개수(1~{@link #MAX_COUNT})
   * @param from 기준 시각(보통 지금)
   */
  public static CronPreviewResponse preview(String cron, String timezone, int count, Instant from) {
    if (count < 1 || count > MAX_COUNT) {
      throw new IllegalArgumentException("미리보기 개수(count)는 1~" + MAX_COUNT + " 사이여야 합니다: " + count);
    }
    if (cron == null || cron.isBlank()) {
      throw new IllegalArgumentException("cron 표현식을 입력하세요 (예: 0 9 * * * → 매일 09:00)");
    }
    String trimmed = cron.trim();
    String normalized = DstAwareCron.normalize(trimmed);
    // 저장 검증(TriggerService.validateScheduleConfig)과 같은 판정 — 매크로(@daily 등)도 받는다
    if (!CronExpression.isValidExpression(normalized)) {
      String reason = ProactiveCron.describeInvalid(trimmed);
      throw new IllegalArgumentException(
          "유효하지 않은 cron 표현식입니다: \"" + trimmed + "\"" + (reason != null ? " — " + reason : ""));
    }
    String tooFrequent = ProactiveCron.describeTooFrequent(normalized);
    if (tooFrequent != null) {
      throw new IllegalArgumentException(tooFrequent);
    }
    String tz = timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone.trim();
    ZoneId zone;
    try {
      zone = ZoneId.of(tz);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("유효하지 않은 시간대입니다: " + tz);
    }
    List<String> times =
        DstAwareCron.of(normalized, zone).nextTimes(from, count).stream()
            .map(Instant::toString)
            .toList();
    return new CronPreviewResponse(normalized, tz, times);
  }
}
