package com.smartfirehub.global.platformdb.tibero;

import static com.smartfirehub.global.platformdb.tibero.TiberoCharsetCheck.PROBE_SQL;
import static com.smartfirehub.global.platformdb.tibero.TiberoCharsetCheck.SAMPLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.platformdb.tibero.TiberoCharsetCheck.Result;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * Tibero 문자셋 기동 확인 빈 시험(WD-1182) — 기동 동작(판정 기록·WARN 로그·예외 없음)과, Tibero 설치본에서만 빈이 뜨는 배선을 본다.
 *
 * <p>배선 시험은 생성자가 둘인 빈에 {@code @Autowired} 가 빠져 Tibero 기동이 멈추던 WD-724 류 실패의 재발 방지다.
 */
@ExtendWith(OutputCaptureExtension.class)
class TiberoCharsetStartupCheckTest {

  private static final int UTF8_BYTES = SAMPLE.getBytes(StandardCharsets.UTF_8).length;

  /** 판정 질의가 한 행(왕복 문자열·바이트 수)을 돌려주는 mock — 판정 시험과 같은 것을 쓴다. */
  private static JdbcTemplate jdbcReturning(String back, int bytes) throws Exception {
    return TiberoCharsetProbeMocks.row(back, bytes);
  }

  @Test
  void 기동_UTF8이면_UTF8_경고없음(CapturedOutput output) throws Exception {
    var check = new TiberoCharsetStartupCheck(jdbcReturning(SAMPLE, UTF8_BYTES));
    assertThatCode(check::afterSingletonsInstantiated).doesNotThrowAnyException();
    assertThat(check.lastResult()).isEqualTo(Result.UTF8);
    assertThat(output.getAll()).doesNotContain("Tibero DB 문자셋");
  }

  @Test
  void 기동_비UTF8이면_NOT_UTF8_기록하고_WARN_예외없음(CapturedOutput output) throws Exception {
    var check = new TiberoCharsetStartupCheck(jdbcReturning(SAMPLE.replace('　', '?'), UTF8_BYTES));
    assertThatCode(check::afterSingletonsInstantiated).doesNotThrowAnyException();
    assertThat(check.lastResult()).isEqualTo(Result.NOT_UTF8);
    assertThat(output.getAll()).contains("Tibero DB 문자셋이 UTF-8 이 아님");
  }

  @Test
  void 조회값이_null이면_UNKNOWN_WARN(CapturedOutput output) throws Exception {
    var check = new TiberoCharsetStartupCheck(jdbcReturning(null, 0));
    assertThatCode(check::afterSingletonsInstantiated).doesNotThrowAnyException();
    assertThat(check.lastResult()).isEqualTo(Result.UNKNOWN);
    assertThat(output.getAll()).contains("Tibero DB 문자셋 확인 결과를 알 수 없음");
  }

  @Test
  @SuppressWarnings("unchecked")
  void 조회_실패해도_예외를_던지지_않고_WARN(CapturedOutput output) {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(eq(PROBE_SQL), any(ResultSetExtractor.class), eq(SAMPLE), eq(SAMPLE)))
        .thenThrow(new DataAccessResourceFailureException("연결 실패"));
    var check = new TiberoCharsetStartupCheck(jdbc);
    assertThatCode(check::afterSingletonsInstantiated).doesNotThrowAnyException();
    assertThat(check.lastResult()).isEqualTo(Result.UNKNOWN);
    assertThat(output.getAll()).contains("Tibero DB 문자셋 확인 실패");
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(TiberoCharsetStartupCheck.class)
          .withBean(DataSource.class, () -> mock(DataSource.class));

  @Test
  @DisplayName("Tibero 컨텍스트에서 빈이 뜨고, 목 DataSource 로 probe 가 실패해도 잡혀 기동은 계속된다")
  void tiberoContext_빈이_뜬다() {
    runner
        .withPropertyValues("firehub.platform-db.type=tibero")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(TiberoCharsetStartupCheck.class);
              assertThat(ctx.getBean(TiberoCharsetStartupCheck.class).lastResult())
                  .isEqualTo(Result.UNKNOWN);
            });
  }

  @Test
  @DisplayName("Tibero 가 아니면 빈이 없다")
  void 비Tibero_컨텍스트에는_빈이_없다() {
    runner
        .withPropertyValues("firehub.platform-db.type=postgres")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(TiberoCharsetStartupCheck.class));
  }
}
