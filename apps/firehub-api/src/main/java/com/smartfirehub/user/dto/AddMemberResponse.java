package com.smartfirehub.user.dto;

/**
 * 멤버 추가 결과.
 *
 * @param created true = 새 계정을 만들었다(웹이 임시 비밀번호를 1회 보여 준다), false = 기존 계정을 이 워크스페이스에 붙였다(비밀번호 불변)
 */
public record AddMemberResponse(Long userId, boolean created) {}
