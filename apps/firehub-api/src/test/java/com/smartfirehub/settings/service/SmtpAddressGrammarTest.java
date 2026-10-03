package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SMTP 호스트·발신자 주소 문법이 web 과 <b>같은 판정</b>을 내리는지 지킨다(#728).
 *
 * <p>값 목록을 이 클래스에 따로 두지 않는다 — web 의 {@code lib/smtp-address.test.ts} 와 <b>같은 픽스처 파일</b>({@code
 * fixtures/smtp-validation-vectors.json})을 읽는다. 목록을 앱마다 따로 들고 있던 동안 두 번 어긋났다: 서버만 파서({@code
 * InternetAddress.validate})를 더 돌려 도메인의 {@code _}·{@code ?} 를 서버만 거부했고, {@code \s}·{@code isBlank}
 * 의 뜻이 JS 와 달라 NBSP·BOM 을 서버만 통과시켰다.
 *
 * <p>지키는 것 세 가지:
 *
 * <ol>
 *   <li>픽스처의 통과·거부 값에 대한 판정이 픽스처와 같다(web 도 같은 파일로 같은 단언을 한다).
 *   <li>자리마다 BMP 전 코드 단위를 넣은 판정이 픽스처의 {@code accepted} 범위와 같다 — 문자 단위로 web 과 같다는 증명이다.
 *   <li><b>문법이 통과시킨 값을 파서 단계가 다시 거부하지 않는다.</b> 파서 단계는 web 에 없으므로, 이것이 깨지면 그만큼이 "칸은 통과, 저장은 400"이다.
 * </ol>
 *
 * <p>DB·스프링이 필요 없는 순수 단위 테스트다. 저장 경로 배선은 {@link SmtpHostAndFromAddressValidationTest} 가 본다.
 */
class SmtpAddressGrammarTest {

  private static final JsonNode FIXTURE = loadFixture();

  private static JsonNode loadFixture() {
    try (InputStream in =
        SmtpAddressGrammarTest.class.getResourceAsStream(
            "/fixtures/smtp-validation-vectors.json")) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("SMTP 시험 벡터 픽스처를 읽지 못했다", e);
    }
  }

  /** 필드 이름 → 서버의 최종 판정(발신자 주소는 문법 + 파서 단계). */
  private static Predicate<String> judge(String field) {
    return switch (field) {
      case "fromAddress" -> SettingsService::isSingleMailAddress;
      case "host" -> SettingsService::isSmtpHostSyntax;
      default -> throw new IllegalArgumentException("모르는 필드: " + field);
    };
  }

  /** 픽스처의 {@code <field>.<accept|reject>} 를 (필드, 값, 기대 판정) 으로 펼친다. */
  static Stream<Arguments> vectors() {
    List<Arguments> rows = new ArrayList<>();
    for (String field : List.of("fromAddress", "host")) {
      FIXTURE
          .get(field)
          .get("accept")
          .forEach(v -> rows.add(Arguments.of(field, v.asText(), true)));
      FIXTURE
          .get(field)
          .get("reject")
          .forEach(v -> rows.add(Arguments.of(field, v.asText(), false)));
    }
    return rows.stream();
  }

  static Stream<Arguments> sweeps() {
    List<Arguments> rows = new ArrayList<>();
    FIXTURE
        .get("charSweep")
        .forEach(
            s ->
                rows.add(
                    Arguments.of(
                        s.get("name").asText(),
                        s.get("field").asText(),
                        s.get("template").asText(),
                        s.get("accepted").asText())));
    return rows.stream();
  }

  @Test
  void 픽스처가_비어_있지_않다() {
    // 읽기 실패·경로 변경으로 공허하게 통과하지 않게 한다 — web 테스트와 같은 하한이다.
    assertThat(FIXTURE.get("fromAddress").get("accept").size()).isGreaterThan(20);
    assertThat(FIXTURE.get("fromAddress").get("reject").size()).isGreaterThan(60);
    assertThat(FIXTURE.get("host").get("accept").size()).isGreaterThan(5);
    assertThat(FIXTURE.get("host").get("reject").size()).isGreaterThan(15);
    assertThat(FIXTURE.get("charSweep").size()).isGreaterThan(10);
  }

  @ParameterizedTest(name = "{0} = [{1}] → {2}")
  @MethodSource("vectors")
  void 픽스처의_값을_web_과_같게_판정한다(String field, String value, boolean accept) {
    assertThat(judge(field).test(value)).as("%s = [%s]", field, value).isEqualTo(accept);
  }

  @ParameterizedTest(name = "{0} = [{1}] 은 문법 판정과 최종 판정이 같다")
  @MethodSource("vectors")
  void 픽스처의_값에서_파서_단계는_문법의_판정을_바꾸지_않는다(String field, String value, boolean accept) {
    if (!field.equals("fromAddress")) return;
    assertThat(SettingsService.isSingleMailAddress(value))
        .isEqualTo(SettingsService.isSmtpSenderSyntax(value));
  }

  /**
   * template 의 {@code {c}} 자리에 U+0000~U+FFFF 를 하나씩 넣어 통과하는 코드 단위를 16진 범위 목록으로 만든다(예: {@code
   * 21,23-27}). web 테스트의 같은 이름 함수와 같은 표기다.
   */
  private static String sweep(Predicate<String> judge, String template) {
    List<String> ranges = new ArrayList<>();
    int start = -1;
    for (int c = 0; c <= 0x10000; c++) {
      boolean ok = c < 0x10000 && judge.test(template.replace("{c}", String.valueOf((char) c)));
      if (ok && start < 0) start = c;
      if (!ok && start >= 0) {
        ranges.add(
            start == c - 1
                ? Integer.toHexString(start)
                : Integer.toHexString(start) + "-" + Integer.toHexString(c - 1));
        start = -1;
      }
    }
    return String.join(",", ranges);
  }

  @ParameterizedTest(name = "{0}({2}) 자리에 통과하는 문자가 web 과 같다")
  @MethodSource("sweeps")
  void 자리마다_통과하는_문자가_web_과_같다(String name, String field, String template, String accepted) {
    assertThat(sweep(judge(field), template)).isEqualTo(accepted);
  }

  @ParameterizedTest(name = "{0}({2}) 자리에서 문법이 통과시킨 문자를 파서가 다시 거부하지 않는다")
  @MethodSource("sweeps")
  void 문법이_통과시킨_값을_파서_단계가_다시_거부하지_않는다(String name, String field, String template, String accepted) {
    if (!field.equals("fromAddress")) return;
    // 최종 판정(문법+파서)의 범위가 문법만의 범위와 같다 = 파서 단계가 한 글자도 더 거르지 않는다.
    assertThat(sweep(SettingsService::isSingleMailAddress, template))
        .isEqualTo(sweep(SettingsService::isSmtpSenderSyntax, template));
  }
}
