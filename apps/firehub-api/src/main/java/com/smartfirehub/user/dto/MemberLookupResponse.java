package com.smartfirehub.user.dto;

/**
 * 멤버 추가 전 이메일 존재 확인 결과(WD-751). 웹 멤버 추가 대화상자가 이 판정으로 이름·임시 비밀번호 칸을 보일지 정한다.
 *
 * @param status 판정. JSON 에는 enum 이름 그대로("NEW" 등) 실린다
 * @param userId 계정 id. NEW 면 null. SUSPENDED 면 웹이 상세 링크({@code /admin/users/<userId>})를 만든다
 * @param name 계정 표시 이름. NEW 면 null. EXISTING 이면 웹이 「누구를 붙이는지」 보여 준다
 */
public record MemberLookupResponse(Status status, Long userId, String name) {

  /** 이메일 판정. 문자열 상수 대신 enum 으로 두어 서비스·시험이 없는 값을 쓰지 못하게 한다. */
  public enum Status {
    /** 계정 없음 — 새로 만든다(이름·임시 비밀번호 필수). */
    NEW,
    /** 계정 있음 + 이 워크스페이스 비멤버 — 멤버십·역할만 붙인다. */
    EXISTING,
    /** 이미 이 워크스페이스의 활성 멤버. */
    MEMBER,
    /** 이미 이 워크스페이스의 멤버지만 정지됨 — 상세에서 재활성화한다. */
    SUSPENDED
  }
}
