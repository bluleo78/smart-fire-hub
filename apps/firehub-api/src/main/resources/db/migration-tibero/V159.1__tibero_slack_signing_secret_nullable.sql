-- V159.1: Tibero 전용 — slack_workspace.signing_secret_enc 의 NOT NULL 을 푼다(WD-709, 스펙 D3).
--
-- 왜: PG 는 이 열이 NOT NULL 이고 슬랙 최초 설치(OAuth)가 빈 문자열 '' 을 넣는다(서명 비밀은 설치 뒤 관리자가 따로 입력).
-- Tibero 는 ''=NULL 이라 같은 INSERT 가 NOT NULL 위반으로 실패한다. 그래서 Tibero 열만 NULL 을 허용하고,
-- MyBatis Tibero 판이 읽을 때 NULL 을 "" 로 돌려 PG 와 같은 값을 만든다(이 열만의 예외 — 다른 열의 '' 는 그대로 계약).
-- PG 짝 마이그레이션은 없다(PG 스키마는 그대로 NOT NULL) — 짝 대조의 NULL 허용 차이는 TiberoBaselineSchemaLiveTest 의
-- RELAXED_NOT_NULL 에 올려 둔다.
ALTER TABLE slack_workspace MODIFY (signing_secret_enc NULL);
