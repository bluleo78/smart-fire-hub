package com.smartfirehub.ontology.exception;

// 이 온톨로지 안에 존재하지 않는 요소(타입/속성/관계) id를 지목했을 때. 400이 아니라 404인 이유:
// 클라이언트는 이 코드로 "남이 이미 지웠다(→ 조용히 재조회해 화면을 맞춘다)"와 "입력이 잘못됐다
// (→ 사용자에게 보여준다)"를 가른다. 둘 다 400이면 낡은 화면을 들고 있던 사용자에게 자기 잘못이
// 아닌 에러를 띄우게 된다.
public class OntologyElementNotFoundException extends RuntimeException {
  public OntologyElementNotFoundException(String message) {
    super(message);
  }
}
