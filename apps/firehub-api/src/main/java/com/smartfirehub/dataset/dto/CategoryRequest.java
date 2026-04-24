package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 카테고리 생성/수정 요청 DTO. 서버 레벨 검증으로 DB 컬럼 길이 초과 방지 (#97) */
public record CategoryRequest(
    @NotBlank(message = "카테고리 이름은 필수입니다.")
    @Size(max = 50, message = "카테고리 이름은 50자 이하여야 합니다.")
    String name,
    @Size(max = 255, message = "설명은 255자 이하여야 합니다.")
    String description) {}
