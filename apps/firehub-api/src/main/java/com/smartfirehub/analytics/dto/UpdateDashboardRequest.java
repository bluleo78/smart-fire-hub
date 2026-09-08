package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateDashboardRequest(
    @Size(max = 200) String name,
    String description,
    Boolean isShared,
    @Min(5) Integer autoRefreshSeconds,
    // autoRefreshSeconds가 null인 이유가 "미제공(변경 없음)"인지 "명시적으로 지움"인지
    // Integer 하나로는 구분할 수 없어 별도 플래그로 분리한다(#568).
    // true면 autoRefreshSeconds가 null이어도 DB 값을 실제로 null로 초기화한다.
    Boolean clearAutoRefresh) {}
