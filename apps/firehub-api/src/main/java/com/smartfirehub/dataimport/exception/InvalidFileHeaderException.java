package com.smartfirehub.dataimport.exception;

/**
 * 임포트 파일의 헤더(첫 행 열 이름)가 행을 「열 이름 → 값」으로 담을 수 없는 모양일 때 던진다(WD-205).
 *
 * <p>이름이 있는 열 가운데 같은 이름이 둘 이상이면(trim·BOM 제거 기준) 뒤 열 값이 앞 열을 조용히 덮어써 매핑 화면과 실제 저장값이 어긋난다. 이를 막기 위해
 * 헤더를 읽는 시점에 거절한다. 빈 이름 열은 검사하지 않는다. 기존 400 매핑({@link UnsupportedFileTypeException} 핸들러)을 그대로 타도록 그
 * 하위 클래스로 두고, 비동기 임포트 잡은 이 타입을 식별해 메시지(한국어 사용자 문구)를 그대로 실패 사유로 쓴다.
 */
public class InvalidFileHeaderException extends UnsupportedFileTypeException {
  public InvalidFileHeaderException(String message) {
    super(message);
  }
}
