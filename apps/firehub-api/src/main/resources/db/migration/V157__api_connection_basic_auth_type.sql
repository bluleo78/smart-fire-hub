-- V157: API 연결 인증 방식에 BASIC(아이디·비밀번호) 을 더한다 (WD-681).
--
-- 예전 CHECK 제약(V26)은 API_KEY·BEARER·OAUTH2 만 받아, BASIC 연결을 저장하면 제약 위반이 났다. 제약을 지우고 BASIC 을 더해 다시 만든다.
-- 이미 저장된 연결(API_KEY·BEARER + baseUrl 의 사용자 정보)은 이관하지 않는다 — 데이터는 건드리지 않고 제약만 바꾼다(사람 결정 Z).
--
-- 트랜잭션 안에서 실행한다(사이드카 .conf 없음) — 실패하면 깨끗이 롤백돼 다음 기동이 다시 시도한다. 제약 교체는 api_connection 에
-- ACCESS EXCLUSIVE 를 잠깐 잡고(작은 설정 테이블이라 새 제약 검증은 금방 끝난다) 긴 대기를 막으려고 락 상한을 둔다.
-- 제약 삭제는 IF EXISTS 로 쓴다(WD-681 리뷰) — 이름이 다르게 남은 환경·이미 지운 뒤 다시 도는 경우에도 실패하지 않는다(Tibero 짝도
-- 사전 뷰로 존재를 확인하고 지운다).
-- Tibero 짝: db/migration-tibero/V157 (MigrationParityTest).
SET LOCAL lock_timeout = '3s';

ALTER TABLE api_connection DROP CONSTRAINT IF EXISTS api_connection_auth_type_check;
ALTER TABLE api_connection ADD CONSTRAINT api_connection_auth_type_check
    CHECK (auth_type IN ('API_KEY', 'BEARER', 'OAUTH2', 'BASIC'));
