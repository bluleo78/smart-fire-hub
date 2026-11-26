-- V173: refresh_token 에 폐기 시각(revoked_at)을 더한다 (WD-1141) — PG V173 과 짝(MigrationParityTest).
--
-- 대상·이유는 PG V173 과 같다: 회전 커밋 뒤 도착한 진 탭의 재제시가 재사용으로 걸려 로그인 패밀리 전체를 폐기하던 것을,
-- 회전 직후 짧은 유예(5초)로 잡기 위해 "이 토큰이 언제 폐기됐는지"를 적어 둔다. 유예 밖·NULL 은 오늘처럼 패밀리 폐기.
--
-- PG 와 다른 표기만 두 가지:
--   ① 열 주석은 PG 파일의 COMMENT ON COLUMN 에만 두고 여기서는 만들지 않는다
--      (주석은 판정에 쓰이지 않고, 검증하지 않은 구문을 마이그레이션에 넣지 않는 규칙에 따른다 — V168 Tibero 와 같은 판단).
--   ② 열 추가는 V160·V161·V163 과 같은 멱등 블록으로 쓴다 — Tibero DDL 은 암묵 커밋이라 중간 실패 뒤
--      repair → migrate 로 다시 돌릴 때 이미 있는 열은 건너뛰어야 하고(V163 이 같은 단일 열 추가인데도
--      이 블록을 쓴 이유), Tibero 에는 Oracle 식 `ADD (col TYPE)` 만 쓴다(PG 와 같은 `ADD COLUMN` 은 쓰지 않는다).
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'REFRESH_TOKEN' AND COLUMN_NAME = 'REVOKED_AT';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE refresh_token ADD (revoked_at TIMESTAMP)';
  END IF;
END;
/
