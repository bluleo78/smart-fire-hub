package com.smartfirehub.settings.service;

import static com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant;
import static com.smartfirehub.support.TenantRlsTestSupport.deleteTenants;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * SMTP 호스트·발신자 주소의 저장 시점 검증(#728).
 *
 * <p>결함: {@code smtp.from_address = "not-an-email"} 과 {@code smtp.host = "  "} 이 204 로
 * 저장됐다. 연결 테스트는 접속만 확인하므로 잘못된 발신자는 실제 메일이 나갈 때에야 실패했고,
 * 공백 호스트는 "저장했는데 미설정"인 상태를 만들었다.
 *
 * <p>매 테스트마다 새 테넌트를 만들고 지운다 — 수용 케이스가 실제로 행을 쓰기 때문이다.
 */
class SmtpHostAndFromAddressValidationTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;
  @Autowired private DSLContext dsl;

  private Long testTenant;

  @BeforeEach
  void useFreshTenant() {
    testTenant = createActiveTenant(dsl, "smtp-address");
    TenantContext.set(testTenant);
  }

  @AfterEach
  void cleanup() {
    deleteTenants(dsl, testTenant);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  /** 거부돼야 하는 값. 메시지에는 필드 이름이 들어 있어야 한다(화면 토스트에 그대로 나간다). */
  @ParameterizedTest(name = "{0} = [{1}] 은 거부된다")
  @CsvSource(
      delimiter = '|',
      ignoreLeadingAndTrailingWhitespace = false,
      textBlock =
          """
          smtp.from_address|not-an-email|발신자 주소
          smtp.from_address|'   '|발신자 주소
          smtp.from_address|' noreply@example.com'|발신자 주소
          smtp.from_address|'noreply@example.com '|발신자 주소
          smtp.from_address|@example.com|발신자 주소
          smtp.from_address|noreply@|발신자 주소
          smtp.from_address|a@b@example.com|발신자 주소
          smtp.from_address|no reply@example.com|발신자 주소
          smtp.from_address|a@example.com, b@example.com|발신자 주소
          smtp.from_address|Fire Hub <not-an-email>|발신자 주소
          smtp.from_address|Fire Hub <noreply@example.com|발신자 주소
          smtp.from_address|team: a@example.com;|발신자 주소
          smtp.host|''|SMTP 호스트
          smtp.host|'  '|SMTP 호스트
          smtp.host|' smtp.example.com'|SMTP 호스트
          smtp.host|'smtp.example.com '|SMTP 호스트
          smtp.host|smtp example.com|SMTP 호스트
          """)
  void 잘못된_값은_필드명을_담은_메시지로_거부된다(String key, String value, String fieldName) {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of(key, value), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(fieldName)
        // 파서(AddressException) 원문이 사용자에게 나가면 안 된다.
        .hasMessageNotContaining("domain")
        .hasMessageNotContaining("Illegal");

    // 거부는 저장 전에 일어난다 — 행이 생기지 않는다.
    assertThat(settingsService.getSmtpConfig()).doesNotContainKey(key);
  }

  /**
   * 정상 값은 그대로 저장된다 — 검증을 넣다가 멀쩡한 발신자를 막지 않았는지 본다. 표시명 형태는
   * 발송 코드({@code EmailDeliveryChannel} 의 {@code helper.setFrom(String)})가 받는 형태다.
   */
  @ParameterizedTest(name = "smtp.from_address = [{0}] 은 저장되고 발송 코드가 From 으로 받는다")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          noreply@example.com
          first.last+tag@mail.ourcompany.co.kr
          alerts@mailhost
          Fire Hub <noreply@example.com>
          스마트 파이어 허브 <noreply@example.com>
          "Hub, Fire" <noreply@example.com>
          <noreply@example.com>
          """)
  void 정상_발신자_주소는_저장되고_발송_코드가_받는다(String value) throws Exception {
    assertThatCode(
            () -> settingsService.updateSettings(Map.of("smtp.from_address", value), null))
        .doesNotThrowAnyException();
    assertThat(settingsService.getSmtpConfig()).containsEntry("smtp.from_address", value);

    // 저장을 허용한 값은 실제 발송 경로의 From 구성도 통과해야 한다 — 검증이 발송 코드보다
    // 넓으면 "저장은 되고 발송에서 실패"가 그대로 남는다.
    var message = new JavaMailSenderImpl().createMimeMessage();
    var helper = new MimeMessageHelper(message, false, "UTF-8");
    assertThatCode(() -> helper.setFrom(value)).doesNotThrowAnyException();
  }

  /** 빈 발신자는 통과한다 — 발송 코드가 기본 발신자로 바꿔 보내는, 동작하는 상태다. */
  @Test
  void 빈_발신자_주소는_저장된다() {
    assertThatCode(() -> settingsService.updateSettings(Map.of("smtp.from_address", ""), null))
        .doesNotThrowAnyException();
    assertThat(settingsService.getSmtpConfig()).containsEntry("smtp.from_address", "");
  }

  @ParameterizedTest(name = "smtp.host = [{0}] 은 저장된다")
  @CsvSource({"smtp.example.com", "relay.internal", "localhost", "127.0.0.1", "[::1]"})
  void 정상_호스트는_저장된다(String host) {
    assertThatCode(() -> settingsService.updateSettings(Map.of("smtp.host", host), null))
        .doesNotThrowAnyException();
    assertThat(settingsService.getSmtpConfig()).containsEntry("smtp.host", host);
  }

  /** 키가 없으면 검증하지 않는다 — 부분 PUT 에서 "키 없음 = 손대지 않음". */
  @Test
  void 호스트와_발신자_키가_없는_저장은_그대로_통과한다() {
    assertThatCode(() -> settingsService.updateSettings(Map.of("smtp.port", "2525"), null))
        .doesNotThrowAnyException();
    assertThat(settingsService.getSmtpConfig()).containsEntry("smtp.port", "2525");
  }
}
