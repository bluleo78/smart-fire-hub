package com.smartfirehub.dataset.exception;

/**
 * 데이터셋 카테고리를 찾지 못했을 때 던진다 — 전역 핸들러가 404 로 바꾸고 message 를 그대로 내보낸다.
 *
 * <p>WD-240: message 는 화면 토스트에 그대로 실리므로 한국어로 쓰고, 내부 id 는 넣지 않는다(예전 「Category not found: N」). 문구가
 * 흩어지지 않도록 기본 생성자로만 만든다.
 */
public class CategoryNotFoundException extends RuntimeException {

  /** 카테고리가 없을 때(조회·수정·삭제, 데이터셋 생성·수정 요청의 categoryId 가 없는 경우 공용). */
  public static final String MESSAGE = "카테고리를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 카테고리를 찾지 못했다는 기본 문구로 만든다. */
  public CategoryNotFoundException() {
    super(MESSAGE);
  }
}
