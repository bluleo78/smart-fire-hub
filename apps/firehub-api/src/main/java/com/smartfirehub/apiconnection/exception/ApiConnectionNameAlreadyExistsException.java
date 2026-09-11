package com.smartfirehub.apiconnection.exception;

/**
 * 동일 테넌트 내에 같은 이름의 API 연결이 이미 존재할 때 던진다 (#647).
 *
 * <p>{@code api_connection.name} 컬럼에는 애초에 유니크 제약이 없어(V93 마이그레이션 주석 참고) 같은 이름의 연결이
 * 여러 개 만들어질 수 있었고, 목록/삭제 확인 다이얼로그가 이름만으로 항목을 표시해 오삭제 위험이 있었다. 이 예외는
 * {@code ApiConnectionService.create()}/{@code update()}에서 저장 전 이름 중복을 막기 위해 사용한다.
 */
public class ApiConnectionNameAlreadyExistsException extends RuntimeException {
  public ApiConnectionNameAlreadyExistsException(String name) {
    super("이미 사용 중인 이름입니다: " + name);
  }
}
