package com.smartfirehub.securitylevel.dto;

import java.time.LocalDateTime;

/**
 * 허용 목록 카드 한 행(목업 s2: 유형·이름·추가한 사람·추가일). type 은 USER 또는 ROLE.
 *
 * <p>WD-292: 항목이 실제로 효과가 있는지 웹이 표시할 수 있게 대상의 열람 등급과 판정 결과를 함께 싣는다(필드 추가만 — 하위 호환).
 *
 * @param subjectClearance 대상의 열람 등급 — 사용자는 유효 열람 등급, 역할은 역할 등급. 사용자에게 역할이 없으면(정지·비활성 포함) null
 * @param sufficientClearance 대상 열람 등급이 데이터셋 등급 이상인가 — false 면 허용 목록에 있어도 그 대상(역할이면 그 역할만으로는)은 볼 수
 *     없다
 */
public record AccessGrantResponse(
    Long id,
    String type,
    Long subjectId,
    String subjectName,
    String grantedByName,
    LocalDateTime grantedAt,
    SubjectClearance subjectClearance,
    boolean sufficientClearance) {}
