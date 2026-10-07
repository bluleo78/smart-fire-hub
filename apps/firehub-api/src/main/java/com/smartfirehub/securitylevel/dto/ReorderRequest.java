package com.smartfirehub.securitylevel.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 등급 순서 — 낮은 등급부터(화면 위→아래). 테넌트의 등급 id 전부를 정확히 한 번씩 담아야 한다. */
public record ReorderRequest(@NotEmpty List<Long> orderedIds) {}
