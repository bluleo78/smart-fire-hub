package com.smartfirehub.analytics.dto;

import com.smartfirehub.dataimport.dto.ExportFormat;
import jakarta.validation.constraints.NotNull;

/** 쿼리 결과 내보내기 요청 — 실행 기록 id 는 경로에, 형식만 본문에 싣는다(클라이언트 rows 는 받지 않는다, 스펙 §4.4). */
public record QueryRunExportRequest(@NotNull(message = "내보내기 형식은 필수입니다.") ExportFormat format) {}
