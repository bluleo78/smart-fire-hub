package com.smartfirehub.dataset.exception;

/**
 * 파이프라인 적재·맞바꿈 도중 출력 데이터셋의 구조(필드·테이블)가 바뀌어 이번 쓰기를 계속할 수 없을 때(WD-670).
 *
 * <p>왜 따로 두나: 예전에는 실행 계획(컬럼 목록)을 세운 뒤 필드가 지워지거나 NOT NULL 필드가 추가되면 DB 원문(「column does not
 * exist」·「relation does not exist」)이 그대로 스텝 오류가 되거나, REPLACE 맞바꿈이 스테이징 동안 들어온 필드 변경을 조용히 지웠다. 이 예외는
 * 쓰기를 되돌린 뒤(트랜잭션 롤백·스테이징 삭제) 사용자가 할 일(다시 실행)을 알려 준다. 메시지가 곧 스텝 오류 문구다.
 */
public class OutputStructureChangedException extends RuntimeException {

  /** 기본 문구 — 무엇이 바뀌었는지 짚을 수 없을 때. */
  public static final String MESSAGE = "실행 중 출력 데이터셋 구조가 바뀌었습니다. 다시 실행해 주세요";

  public OutputStructureChangedException() {
    super(MESSAGE);
  }

  public OutputStructureChangedException(Throwable cause) {
    super(MESSAGE, cause);
  }

  /** 어느 필드가 사라졌는지 아는 경우 — 필드 이름을 문구에 싣는다. */
  public static OutputStructureChangedException missingField(String field) {
    return new OutputStructureChangedException(
        "실행 중 출력 데이터셋 구조가 바뀌었습니다(필드 '" + field + "' 이(가) 없습니다). 다시 실행해 주세요", null);
  }

  private OutputStructureChangedException(String message, Throwable cause) {
    super(message, cause);
  }
}
