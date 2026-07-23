package com.smartfirehub.admin.embedding;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 내부 임베딩 실행 요청 — 벡터화할 텍스트 배치. */
public record EmbedRequest(@NotNull List<String> texts) {}
