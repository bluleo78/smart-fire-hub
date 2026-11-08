package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 검증 응답 모양 스텁(teststub.validation.ValidationStubController, WD-640)이 전체 컨텍스트에 새지 않는지 확인한다.
 *
 * <p>스텁이 com.smartfirehub 아래 @RestController 면 모든 @SpringBootTest 컨텍스트에 /test/validation/* 가 매핑된다.
 * 스캔 범위 밖에 두고 WebMvc 슬라이스만 @Import 하므로 여기서는 보이지 않아야 한다. Spring MVC 표준 예외 스텁
 * (teststub.mvcerror.SpringMvcStandardErrorStubController, WD-649 — 예전에는 스캔 패키지라 /test/wd626/* 가 모든
 * 컨텍스트에 붙었다)도 같은 방식이라 함께 확인한다.
 */
class ValidationStubIsolationTest extends IntegrationTestBase {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mapping;

  @Test
  void fullContext_doesNotMapValidationStub() {
    assertThat(mapping.getHandlerMethods().keySet())
        .as("전체 컨텍스트의 요청 매핑")
        .isNotEmpty()
        .noneMatch(
            info ->
                info.getPatternValues().stream().anyMatch(p -> p.startsWith("/test/validation")));
  }

  /** Spring MVC 표준 예외 스텁(WD-626)도 전체 컨텍스트에 매핑되지 않는다(WD-649). */
  @Test
  void fullContext_doesNotMapMvcErrorStub() {
    assertThat(mapping.getHandlerMethods().keySet())
        .as("전체 컨텍스트의 요청 매핑")
        .isNotEmpty()
        .noneMatch(
            info -> info.getPatternValues().stream().anyMatch(p -> p.startsWith("/test/wd626")));
  }
}
