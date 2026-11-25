-- V170: saved_query.folder 중 공백·Cf 만인 값을 NULL 로 비운다 (WD-1103) — PG V170 과 짝(MigrationParityTest).
--
-- 뜻은 PG V170 머리말 참고. Tibero 는 정규식에 Unicode 속성(`\p{Cf}`)이 없어 아래 알려진 Cf 코드점 실자 목록으로 같은 일을 한다.
-- 목록이 `TextRules` 보다 좁으면 잔여 행은 읽기 가드(`TextRules.hasText`·웹 `hasVisibleText`)가 계속 막는다.
--
-- PG 와 다른 표기:
--   - PG 는 Java 마이그레이션에서 `TextRules.hasText` 로 판정해 목록을 문자열로 적지 않고, Tibero 는 아래 문자 클래스에
--     Cf·유니코드 공백 실자를 적는다(코드점은 맨 아래 주석). 목록을 바꿀 때는 TextRules 와 함께 고칠 것.
--   - 목록 차이로 잔여 행이 남아도 위 읽기 가드가 계속 막으므로 화면·검증으로 새어 나가지 않는다.
--
-- 빈 값 비교(WD-1103 리뷰): Tibero 는 `''` 를 NULL 로 보므로 `= ''` 은 비교 결과가 NULL 이 되어 한 행도 고르지 못한다.
--   그래서 잘라 낸 결과가 비었는지를 `IS NULL` 로 본다.
--
-- 암묵 커밋: 이 파일은 DDL 없는 UPDATE 한 문장뿐이라 Tibero DDL 의 암묵 커밋(중간 실패 시 반쯤 적용)과 무관하다.
--   DML 이라 실패하면 롤백되고, 다시 돌려도 결과가 같다(멱등).
--
-- Cf 코드점(문자 클래스에 그대로 들어간 실자): U+00AD U+200B U+200C U+200D U+200E U+200F U+2060 U+2066~U+206F U+FEFF
-- 유니코드 공백(같이 넣은 실자): U+00A0(NBSP) U+1680 U+2000~U+200A U+202F U+205F U+3000
--   `[[:space:]]` 에만 기대면 NBSP·전각 공백이 TextRules `(?U)\s` 와 어긋나므로(WD-1103 리뷰) 실자로 함께 적는다.
--   `(?U)\s` 중 폴더 이름에 올 수 없는 줄바꿈 문자(U+0085·U+2028·U+2029)는 파일에 새는 위험이 있어 뺐다 — 잔여 행은 위 읽기 가드가 막는다.
UPDATE saved_query
   SET folder = NULL
 WHERE folder IS NOT NULL
   AND REGEXP_REPLACE(folder, '[[:space:]­​‌‍‎‏⁠⁦⁧⁨⁩⁪⁫⁬⁭⁮⁯﻿               　]', '') IS NULL;
