package com.smartfirehub.dataset.rowsearch.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 검색 대상 필드 전체 교체. 빈 목록 = 검색 끄기. */
public record UpdateSearchIndexRequest(@NotNull List<String> fields) {}
