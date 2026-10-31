package com.smartfirehub.dataimport.dto;

import java.util.List;

/**
 * 사전 검증 응답. 전량 검증이 아니라 파일 앞 {@code sampleSize}행만 검사한 결과다(스키마/매핑 빠른 확인용). 전체 데이터 검증은 임포트 잡에서 수행된다.
 * {@code sampled}는 항상 true이며, UI가 "샘플 검사"임을 표기하는 근거다.
 *
 * <p>{@code roundedDecimalValues}: 샘플의 유효 행 중 소수점 아래 6자리를 넘어 저장 때 6자리로 반올림될 소수 값의 수(WD-371). 임포트는
 * 이런 값을 거부하지 않고 반올림해 받으므로, 화면이 사용자에게 미리 알린다.
 */
public record ImportValidateResponse(
    int sampleSize,
    int validRows,
    int errorRows,
    boolean sampled,
    List<ValidationErrorDetail> errors,
    int roundedDecimalValues) {}
