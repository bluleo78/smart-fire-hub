package com.smartfirehub.global.platformdb.tibero;

import static com.smartfirehub.global.platformdb.tibero.TiberoCharsetCheck.PROBE_SQL;
import static com.smartfirehub.global.platformdb.tibero.TiberoCharsetCheck.SAMPLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.platformdb.tibero.TiberoCharsetCheck.Result;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** Tibero 문자셋 판정 시험(WD-1182) — 스프링 없이 JdbcTemplate mock 으로 돈다. */
class TiberoCharsetCheckTest {

  private static final int UTF8_BYTES = SAMPLE.getBytes(StandardCharsets.UTF_8).length;

  @Test
  void judge_왕복이_같고_바이트수가_UTF8이면_UTF8() {
    assertThat(TiberoCharsetCheck.judge(SAMPLE, UTF8_BYTES)).isEqualTo(Result.UTF8);
  }

  @Test
  void judge_물음표로_접히면_NOT_UTF8() {
    assertThat(TiberoCharsetCheck.judge(SAMPLE.replace('　', '?'), UTF8_BYTES))
        .isEqualTo(Result.NOT_UTF8);
  }

  @Test
  void judge_바이트수가_다르면_NOT_UTF8() {
    assertThat(TiberoCharsetCheck.judge(SAMPLE, UTF8_BYTES - 1)).isEqualTo(Result.NOT_UTF8);
  }

  @Test
  void judge_null이면_UNKNOWN() {
    assertThat(TiberoCharsetCheck.judge(null, 3)).isEqualTo(Result.UNKNOWN);
    assertThat(TiberoCharsetCheck.judge(SAMPLE, null)).isEqualTo(Result.UNKNOWN);
  }

  @Test
  void probe_바이트수가_SQL_NULL이면_UNKNOWN() throws Exception {
    assertThat(TiberoCharsetCheck.probe(TiberoCharsetProbeMocks.row(SAMPLE, null)))
        .isEqualTo(Result.UNKNOWN);
  }

  @Test
  void probe_행이_없으면_UNKNOWN() throws Exception {
    assertThat(TiberoCharsetCheck.probe(TiberoCharsetProbeMocks.noRow()))
        .isEqualTo(Result.UNKNOWN);
  }

  @Test
  @SuppressWarnings("unchecked")
  void probe_추출기가_null을_돌려주면_UNKNOWN() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(eq(PROBE_SQL), any(ResultSetExtractor.class), eq(SAMPLE), eq(SAMPLE)))
        .thenReturn(null);
    assertThat(TiberoCharsetCheck.probe(jdbc)).isEqualTo(Result.UNKNOWN);
  }

  @Test
  void probe_UTF8_왕복이면_UTF8() throws Exception {
    assertThat(TiberoCharsetCheck.probe(TiberoCharsetProbeMocks.row(SAMPLE, UTF8_BYTES)))
        .isEqualTo(Result.UTF8);
  }
}
