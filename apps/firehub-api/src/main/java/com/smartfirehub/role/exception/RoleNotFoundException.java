package com.smartfirehub.role.exception;

/**
 * 역할을 찾지 못했을 때 던진다 — 전역 핸들러가 404 로 바꾸고 message 를 그대로 내보낸다.
 *
 * <p>WD-287: message 는 화면 토스트와 AI 챗에 그대로 실리므로 한국어로 쓰고, 내부 id 는 넣지 않는다(예전 「Role not found: N」). 문구가
 * 호출처마다 흩어지지 않도록 기본 생성자·팩토리로만 만들게 한다(임의 문자열 생성자는 두지 않는다 — WD-226·WD-240 방식).
 */
public class RoleNotFoundException extends RuntimeException {

  /** 역할이 없을 때 — 열어 둔 역할 상세가 다른 곳에서 삭제된 뒤 조회·저장하는 경우가 대표적이다. */
  public static final String MESSAGE = "역할을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 역할을 찾지 못했다는 기본 문구로 만든다. */
  public RoleNotFoundException() {
    this(MESSAGE);
  }

  private RoleNotFoundException(String message) {
    super(message);
  }

  /**
   * 기본 시스템 역할(USER·ADMIN)이 없을 때의 예외 — 지워질 수 없는 역할이라 「이미 삭제」가 아니라 워크스페이스 구성 손상을 알린다. 역할 이름은 내부 id 가
   * 아니라 화면에도 보이는 값이라 문구에 넣는다.
   */
  public static RoleNotFoundException systemRole(String name) {
    return new RoleNotFoundException("기본 역할(" + name + ")을 찾을 수 없습니다. 워크스페이스 구성을 확인해 주세요.");
  }
}
