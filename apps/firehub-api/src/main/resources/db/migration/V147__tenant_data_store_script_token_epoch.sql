-- V147: 테넌트별 스크립트 토큰 세대 번호(WD-78, Ruling 15).
-- Tibero 는 등급 정의 변경(순서 변경·삭제·허용 목록 해제)으로 슬롯 k 의 GRANT 가 넓어져도 이미 실행 중인 스크립트의
-- 세션을 끊을 수 없다(PG 는 끊는다). 그래서 넓히는 GRANT 를 걸기 전에 이 번호를 올리고 먼저 커밋한다. 스크립트 토큰(Task 13)이
-- 발급 시점의 번호를 싣고, db-executor 는 기술자의 번호보다 낮은 토큰을 거부한다 — 옛 위치로 받은 슬롯 토큰이 넓어진 슬롯으로 읽지 못한다.
-- 전역 테이블(RLS 없음)의 컬럼이라 컨텍스트 없이 읽고 쓴다. 기존 행은 0 에서 시작한다.
ALTER TABLE tenant_data_store
    ADD COLUMN IF NOT EXISTS script_token_epoch BIGINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN tenant_data_store.script_token_epoch IS
    '스크립트 토큰 세대 번호 — Tibero 등급 정의 변경 동기화가 GRANT 전에 올린다. 토큰이 이 값보다 낮으면 db-executor 가 거부(Task 13)';
