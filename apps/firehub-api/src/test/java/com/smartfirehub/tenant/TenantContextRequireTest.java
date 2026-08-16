package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantContext#require()} / {@link TenantContext#require(String)} 와
 * {@link MissingTenantScopeException} 의 계약을 고정하는 단위 테스트.
 *
 * <p>이 오버로드는 {@code require()} 의 메시지가 "배경 잡 예약" 문맥에 하드코딩돼 있어서 다른
 * 호출 지점(Slack 웹훅 본 처리)이 전용 예외 타입을 따로 만들었던 것을 되돌리기 위해 추가됐다.
 * 세 번째 지점이 또 그렇게 하지 않도록, 문맥 문자열을 인자로 받는 오버로드로 통일한다.
 */
class TenantContextRequireTest {

  @AfterEach
  void clearContext() {
    TenantContext.clear();
  }

  /** 새 오버로드가 호출부의 문맥 설명을 메시지에 그대로 담는지. */
  @Test
  void requireWithContextDescriptionNamesTheCallSite() {
    TenantContext.clear();
    assertThatThrownBy(() -> TenantContext.require("Slack inbound 본 처리 (team=T123)"))
        .isInstanceOf(MissingTenantScopeException.class)
        .hasMessageContaining("Slack inbound 본 처리")
        .hasMessageContaining("T123");
  }

  /** 기존 무인자 require() 는 삭제하지 않고 새 오버로드에 위임한다 — 메시지 취지("배경 잡")는 유지. */
  @Test
  void legacyRequireStillThrowsAndCarriesItsOwnContext() {
    TenantContext.clear();
    assertThatThrownBy(TenantContext::require)
        .isInstanceOf(MissingTenantScopeException.class)
        .hasMessageContaining("배경 잡");
  }

  /** 정상 경로 — 컨텍스트가 있으면 예외 없이 테넌트 ID를 그대로 돌려준다. */
  @Test
  void requireReturnsTenantWhenPresent() {
    TenantContext.set(42L);
    assertThat(TenantContext.require()).isEqualTo(42L);
    assertThat(TenantContext.require("아무 문맥")).isEqualTo(42L);
  }

  /**
   * ⚠ 계약 고정: {@link MissingTenantScopeException} 은 반드시 {@link IllegalStateException} 을
   * 상속해야 한다.
   *
   * <p>{@code GlobalExceptionHandler.handleIllegalState} 가 이 계층을 409 로 변환하고,
   * {@code SlackInboundService.dispatch} 의 catch 가 이 상속 관계 위에 있다. 상속을 끊으면 두 지점
   * 모두 조용히 깨져 이 예외가 처리되지 않은 500 으로 표면화된다 — 이 테스트가 그 회귀를 막는다.
   */
  @Test
  void missingTenantScopeExceptionIsAnIllegalStateException() {
    assertThat(new MissingTenantScopeException("x")).isInstanceOf(IllegalStateException.class);
  }
}
