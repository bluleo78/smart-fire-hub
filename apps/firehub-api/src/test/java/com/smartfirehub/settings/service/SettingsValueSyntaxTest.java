package com.smartfirehub.settings.service;

import static com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant;
import static com.smartfirehub.support.TenantRlsTestSupport.deleteTenants;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 설정 값의 <b>표기(문법)</b> 검증(#727). 범위 검증은 {@code SettingsServiceTest}·
 * {@code SmtpSettingsServiceTest} 가 본다 — 여기는 "Java 파서가 우연히 받아 주던 표기"와
 * "Java 예외 원문이 그대로 나가던 표기"를 고정한다.
 *
 * <p>결함은 두 방향이었다. (1) {@code Integer.parseInt("+5")}·{@code Double.parseDouble("NaN")}·
 * {@code "0.5d"} 는 파싱에 성공해 204 로 저장됐고({@code NaN} 은 {@code v < 0 || v > 1} 을 둘 다
 * 통과한다), 빈 {@code ai.model} 은 검사 자체가 없었다. (2) {@code "1e1"}·{@code "5.0"} 은
 * {@code NumberFormatException} 원문({@code For input string: "1e1"})이 400 메시지가 됐다.
 *
 * <p>매 테스트마다 새 테넌트를 만들고 지운다 — 수용 케이스가 실제로 행을 쓰기 때문이다.
 */
class SettingsValueSyntaxTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;
  @Autowired private DSLContext dsl;

  private Long testTenant;

  @BeforeEach
  void useFreshTenant() {
    testTenant = createActiveTenant(dsl, "settings-syntax");
    TenantContext.set(testTenant);
  }

  @AfterEach
  void cleanup() {
    deleteTenants(dsl, testTenant);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  /**
   * 거부돼야 하는 표기. 세 번째 열은 메시지에 들어 있어야 하는 필드 이름 — 사용자가 어느 칸이
   * 틀렸는지 알 수 있어야 한다(화면이 이 메시지를 토스트로 그대로 보여준다).
   */
  @ParameterizedTest(name = "{0} = [{1}] 은 거부된다")
  @CsvSource(
      delimiter = '|',
      ignoreLeadingAndTrailingWhitespace = false,
      textBlock =
          """
          ai.temperature|NaN|Temperature
          ai.temperature|0.5d|Temperature
          ai.temperature|0.5f|Temperature
          ai.temperature|+0.5|Temperature
          ai.temperature|1e-1|Temperature
          ai.temperature|0x0p0|Temperature
          ai.temperature|Infinity|Temperature
          ai.temperature| 0.5|Temperature
          ai.temperature|''|Temperature
          ai.max_turns|+5|최대 턴 수
          ai.max_turns|1e1|최대 턴 수
          ai.max_turns|5.0|최대 턴 수
          ai.max_turns|99999999999|최대 턴 수
          ai.max_turns|''|최대 턴 수
          ai.max_tokens|+100|최대 토큰 수
          ai.max_tokens|1e3|최대 토큰 수
          ai.session_max_tokens|+50000|세션 최대 토큰 수
          ai.session_max_tokens|5e4|세션 최대 토큰 수
          smtp.port|+25|SMTP 포트
          smtp.port|587.0|SMTP 포트
          smtp.port|5e2|SMTP 포트
          smtp.port|99999999999|SMTP 포트
          ai.model|''|모델
          ai.model|'   '|모델
          """)
  void 잘못된_표기는_필드명을_담은_메시지로_거부된다(String key, String value, String fieldName) {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of(key, value), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(fieldName)
        // Java 예외 원문이 사용자에게 나가면 안 된다.
        .hasMessageNotContaining("For input string")
        .hasMessageNotContaining("empty String");

    // 거부는 저장 전에 일어난다 — 그 값이 남지 않는다(ai.* 는 미저장이면 코드 기본값이 나온다).
    assertThat(settingsService.getValue(key)).isNotEqualTo(Optional.of(value));
  }

  /** 정상 표기는 그대로 저장된다 — 문법을 조이다가 멀쩡한 값을 막지 않았는지 본다. */
  @ParameterizedTest(name = "{0} = [{1}] 은 저장된다")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          ai.temperature|0
          ai.temperature|1
          ai.temperature|1.0
          ai.temperature|0.75
          ai.max_turns|10
          ai.max_turns|050
          ai.max_tokens|16384
          ai.session_max_tokens|50000
          smtp.port|587
          ai.model|claude-sonnet-5
          ai.model|anthropic/claude-sonnet-5
          """)
  void 정상_표기는_저장된다(String key, String value) {
    assertThatCode(() -> settingsService.updateSettings(Map.of(key, value), null))
        .doesNotThrowAnyException();
    assertThat(settingsService.getValue(key)).contains(value);
  }
}
