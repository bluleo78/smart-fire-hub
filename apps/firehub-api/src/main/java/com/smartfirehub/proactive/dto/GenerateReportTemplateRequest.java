package com.smartfirehub.proactive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 목표 기반 리포트 양식 섹션 생성 요청(WD-455). 사용자의 비즈니스 질문(목표)만 받는다 — 생성 결과는 저장하지 않고 돌려주기만 하며, 저장·작업 연결은 기존 양식
 * 생성·작업 생성 흐름(WD-297)이 그대로 맡는다.
 *
 * @param question 비즈니스 질문. 길이 상한은 ai-agent 라우트(template-generate.ts)의 상한과 같다.
 */
public record GenerateReportTemplateRequest(
    @NotBlank(message = "비즈니스 질문을 입력하세요.") @Size(max = 2000, message = "비즈니스 질문은 2000자 이하로 입력하세요.")
        String question) {}
