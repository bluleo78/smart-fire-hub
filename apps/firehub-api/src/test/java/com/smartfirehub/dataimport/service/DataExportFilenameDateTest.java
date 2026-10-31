package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 내보내기 파일 이름 날짜(WD-383) — JVM 시간대와 무관하게 Asia/Seoul 날짜를 붙이는지 확인한다. 운영 Docker 는 JVM 이 UTC 라, 예전
 * LocalDate.now() 는 한국 시간 0~9시에 내보낸 파일에 전날 날짜를 붙였다.
 */
class DataExportFilenameDateTest {

  @Test
  void 한국_새벽은_UTC_로는_전날이어도_한국_날짜를_쓴다() {
    // UTC 2026-10-28 18:15 = KST 10-29 03:15
    assertThat(DataExportService.exportDateStamp(Instant.parse("2026-10-28T18:15:00Z")))
        .isEqualTo("20261029");
  }

  @Test
  void 한국_날짜가_바뀌기_직전은_그날_날짜다() {
    // UTC 2026-10-29 14:59 = KST 10-29 23:59
    assertThat(DataExportService.exportDateStamp(Instant.parse("2026-10-29T14:59:00Z")))
        .isEqualTo("20261029");
  }
}
