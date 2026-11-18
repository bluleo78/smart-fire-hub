package com.smartfirehub.global.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.smartfirehub.global.util.Jackson2WebClientCodecs;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 직접 만드는 WebClient 는 {@link Jackson2WebClientCodecs} 로만 만든다 — JSON 코덱을 Jackson 2 로 고정한다(WD-792).
 *
 * <p><b>왜.</b> Spring Framework 7 의 기본 코덱은 클래스패스에 Jackson 3 이 있으면 Jackson 3 을 먼저 고른다. 자동 구성 빌더는
 * 설정({@code spring.http.codecs.preferred-json-mapper: jackson2})으로 맞췄지만, {@code
 * WebClient.builder()}·{@code ExchangeStrategies.builder()} 로 직접 만든 클라이언트는 그 설정을 거치지 않아 조용히 Jackson
 * 3 으로 바뀐다(com.fasterxml {@code JsonNode} 디코딩 실패, Jackson 2 전용 설정 무시). 호출처마다 기억에 맡기면 다음에 추가되는 곳이
 * 빠지므로, 두 정적 빌더는 도우미 안에서만 부르게 한다(메서드 참조도 접근으로 센다). Jackson 3 전환(WD-793) 때 이 규칙과 도우미를 함께 지운다.
 */
class WebClientJackson2CodecsArchitectureTest {

  /** 프로덕션 클래스 그래프 — 다른 아키텍처 규칙과 같은 옵션(테스트·jOOQ 생성 코드 제외). 끝나면 놓아 스위트 힙을 아낀다. */
  private static JavaClasses PROD;

  @BeforeAll
  static void importProductionClasses() {
    PROD =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> !location.contains("/com/smartfirehub/jooq/"))
            .importPackages("com.smartfirehub");
  }

  @AfterAll
  static void releaseProductionClasses() {
    PROD = null;
  }

  @Test
  void onlyCodecsHelperCallsWebClientBuilder() {
    noClasses()
        .that()
        .doNotBelongToAnyOf(Jackson2WebClientCodecs.class)
        .should()
        .callMethod(WebClient.class, "builder")
        .because("직접 빌더는 Jackson2WebClientCodecs.webClientBuilder() 로 만든다(Jackson 2 코덱 고정)")
        .check(PROD);
  }

  @Test
  void onlyCodecsHelperCallsExchangeStrategiesBuilder() {
    noClasses()
        .that()
        .doNotBelongToAnyOf(Jackson2WebClientCodecs.class)
        .should()
        .callMethod(ExchangeStrategies.class, "builder")
        .because("교환 전략은 Jackson2WebClientCodecs.exchangeStrategies(int) 로 만든다(Jackson 2 코덱 고정)")
        .check(PROD);
  }
}
