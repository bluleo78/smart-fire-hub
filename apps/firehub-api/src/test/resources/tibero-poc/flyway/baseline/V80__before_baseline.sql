-- WD-488 PoC: baseline 버전 이하라 적용되면 안 되는 마이그레이션.
CREATE TABLE must_not_exist (id NUMBER PRIMARY KEY);
