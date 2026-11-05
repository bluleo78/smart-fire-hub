-- WD-488 PoC: 첫 DDL 은 성공, 둘째 DDL 은 구문 오류 → Tibero DDL 암묵 커밋으로 반쯤 적용된 상태를 만든다.
CREATE TABLE half_a (id NUMBER PRIMARY KEY);
CREATE TABLE half_b (id NUMBER PRIMARY KEY, broken BROKEN_TYPE_X);
