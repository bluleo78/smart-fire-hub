package com.smartfirehub.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.platform.dto.TenantSummaryResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 테넌트 감사 공용 details 앞머리(WD-1180: tenantName 포함) 단위 테스트. */
class PlatformAuditRecorderDetailsTest {

  @Test
  void tenantAuditDetails_includesIdSlugAndName_inOrder() {
    TenantSummaryResponse tenant =
        new TenantSummaryResponse(7L, "acme", "에이씨엠이", "ACTIVE", 3, LocalDateTime.now());

    Map<String, Object> details = PlatformAuditRecorder.tenantAuditDetails(7L, tenant);

    assertThat(details)
        .containsEntry("tenantId", 7L)
        .containsEntry("tenantSlug", "acme")
        .containsEntry("tenantName", "에이씨엠이");
    assertThat(List.copyOf(details.keySet())).containsExactly("tenantId", "tenantSlug", "tenantName");
  }

  @Test
  void tenantAuditDetails_bySlugAndName_matchesSummaryOverload() {
    Map<String, Object> details = PlatformAuditRecorder.tenantAuditDetails(7L, "acme", "에이씨엠이");

    assertThat(details)
        .containsEntry("tenantId", 7L)
        .containsEntry("tenantSlug", "acme")
        .containsEntry("tenantName", "에이씨엠이");
  }
}
