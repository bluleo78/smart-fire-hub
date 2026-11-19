package com.smartfirehub.global.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.smartfirehub.global.util.AppJsonWebClients;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 직접 만드는 WebClient 는 {@link AppJsonWebClients} 로만 만든다 — JSON 코덱을 앱 매퍼와 같은 설정으로 맞춘다(WD-793).
 *
 * <p><b>왜.</b> {@code WebClient.builder()}·{@code ExchangeStrategies.builder()} 로 직접 만든 클라이언트는 Boot
 * 자동 구성을 거치지 않아 Spring 기본 Jackson 3 매퍼를 쓴다 — java.sql.Date 가 UTC 시각 문자열로 나가 하루 어긋나고, 응답의 원시 타입 자리
 * null 을 거부한다(코드 리뷰 지적 1·2). 호출처마다 기억에 맡기면 다음에 추가되는 곳이 빠지므로, 두 정적 빌더는 팩토리 안에서만 부르게 한다(메서드 참조도 접근으로
 * 센다).
 */
class AppJsonWebClientsArchitectureTest {

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
  void onlyFactoryCallsWebClientBuilder() {
    noClasses()
        .that()
        .doNotBelongToAnyOf(AppJsonWebClients.class)
        .should()
        .callMethod(WebClient.class, "builder")
        .because("직접 빌더는 AppJsonWebClients.webClientBuilder() 로 만든다(앱 매퍼와 같은 JSON 코덱)")
        .check(PROD);
  }

  @Test
  void onlyFactoryCallsExchangeStrategiesBuilder() {
    noClasses()
        .that()
        .doNotBelongToAnyOf(AppJsonWebClients.class)
        .should()
        .callMethod(ExchangeStrategies.class, "builder")
        .because("교환 전략은 AppJsonWebClients.exchangeStrategies(int) 로 만든다(앱 매퍼와 같은 JSON 코덱)")
        .check(PROD);
  }
}
