package com.smartfirehub.dataset.exception;

/**
 * 데이터셋·카테고리 이름이 이미 쓰이고 있을 때 던진다 — 전역 핸들러가 409 로 바꾸고 message 를 그대로 내보낸다.
 *
 * <p>데이터셋 이름 충돌 문구는 호출처(DatasetService)가 정한다. 카테고리 이름 충돌은 화면·AI 도구·API 가 같은 한국어 문구를 보도록 {@link
 * #categoryName()} 팩토리로만 만든다(WD-241, 예전 영어 「Category name already exists: …」).
 */
public class DuplicateDatasetNameException extends RuntimeException {

  /** 카테고리 이름 중복 문구 — 웹 카테고리 화면이 스스로 띄우던 문구와 같다. 받은 이름은 싣지 않는다. */
  public static final String CATEGORY_NAME_MESSAGE = "이미 사용 중인 카테고리 이름입니다.";

  public DuplicateDatasetNameException(String message) {
    super(message);
  }

  /** 카테고리 이름이 (앞뒤 공백·대소문자 무시로) 다른 카테고리와 겹칠 때의 예외를 만든다. */
  public static DuplicateDatasetNameException categoryName() {
    return new DuplicateDatasetNameException(CATEGORY_NAME_MESSAGE);
  }
}
