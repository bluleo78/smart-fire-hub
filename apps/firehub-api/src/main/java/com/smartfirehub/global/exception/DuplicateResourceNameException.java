package com.smartfirehub.global.exception;

import com.smartfirehub.global.naming.ResourceKind;
import org.springframework.http.HttpStatus;

/**
 * 같은 소유자가 이미 쓰는 이름으로 자산을 만들거나 바꾸려 할 때의 예외 — 409 + {@link #CODE}(WD-824). 화면 토스트·AI 도구가 같은 문장을 보이도록 문구를 자산
 * 종류별로 한 곳({@link #messageFor})에서 만든다.
 */
public class DuplicateResourceNameException extends CodedApiException {
  /** 이름 중복을 뜻하는 기계용 오류 코드. */
  public static final String CODE = "DUPLICATE_NAME";

  public DuplicateResourceNameException(ResourceKind kind) {
    super(HttpStatus.CONFLICT, CODE, messageFor(kind));
  }

  /** 종류별 안내 문구 — 「이미 있는 쿼리 이름입니다. 다른 이름을 입력하세요.」 꼴. */
  public static String messageFor(ResourceKind kind) {
    return "이미 있는 " + kind.label() + " 이름입니다. 다른 이름을 입력하세요.";
  }
}
