package com.smartfirehub.dataimport.dto;

import java.util.List;

/**
 * 사전 검증 응답. 전량 검증이 아니라 파일 앞 {@code sampleSize}행만 검사한 결과다(스키마/매핑 빠른 확인용).
 * 전체 데이터 검증은 임포트 잡에서 수행된다. {@code sampled}는 항상 true이며, UI가 "샘플 검사"임을 표기하는 근거다.
 */
public record ImportValidateResponse(
    int sampleSize, int validRows, int errorRows, boolean sampled, List<ValidationErrorDetail> errors) {}
