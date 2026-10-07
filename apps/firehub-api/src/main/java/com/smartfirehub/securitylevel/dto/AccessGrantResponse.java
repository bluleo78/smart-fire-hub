package com.smartfirehub.securitylevel.dto;

import java.time.LocalDateTime;

/** 허용 목록 카드 한 행(목업 s2: 유형·이름·추가한 사람·추가일). type 은 USER 또는 ROLE. */
public record AccessGrantResponse(
    Long id,
    String type,
    Long subjectId,
    String subjectName,
    String grantedByName,
    LocalDateTime grantedAt) {}
