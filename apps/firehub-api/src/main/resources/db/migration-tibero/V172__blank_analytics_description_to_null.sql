-- V172: saved_query·chart·dashboard 의 description 중 글자가 하나도 없는 값(빈 문자열·공백·Cf·Cc·채움 문자만)을 NULL 로 비운다 (WD-1161) — PG V172 와 짝(MigrationParityTest).
--
-- 무엇을: 저장 쿼리·차트·대시보드 저장소는 이제 글자 없는 설명을 NULL 로 저장한다(TextRules). 이 파일은 그 전에 들어간 옛 행의 description 을
--   같은 기준으로 NULL 로 정리한다. 이미 NULL 이거나 글자가 있는 행은 건드리지 않으므로 다시 돌려도 결과가 같다(멱등).
--   saved_query.folder 는 V171 이 BMP Cc·줄 구분자를 빼 두었으므로 같은 목록으로 한 번 더 비운다(PG V170·V171 의 `(?U)\s`·`\p{Cc}` 와 맞춤).
-- 왜 새 버전인가: V170·V171 은 이미 적용됐을 수 있어 파일을 고치면 체크섬이 달라져 기동이 멈춘다(forward-only). 그래서 V171 은 그대로 두고 여기서 잇는다.
-- Tibero 는 정규식에 Unicode 속성(`\p{Cf}`)이 없어 알려진 코드점 목록으로 같은 일을 한다. 목록이 TextRules 보다 좁아 남는 잔여 행은
--   읽기 가드(`TextRules.hasText`·웹 `hasVisibleText`)가 계속 막는다.
--
-- 목록(네 문장 모두 같다): V171 목록 + BMP Cc(U+0001-001F, U+007F-009F — 줄바꿈 U+000A·000D·0085 포함) + 줄·문단 구분자 U+2028·2029.
--   V171 은 「폴더 이름에 줄바꿈이 올 수 없다」며 줄바꿈 계열을 뺐지만, 그런 값만인 옛 행은 PG 가 이미 비웠으므로 Tibero 도 같이 비운다(WD-1161 코드 리뷰).
-- UNISTR 로 쓴 까닭: 눈에 안 보이는 실자는 에디터·복사가 지우거나 바꿀 수 있어 파일에는 ASCII 만 두고 `UNISTR('\XXXX…')` 로 코드점을 하나씩 적는다.
--   범위(`-`)는 쓰지 않는다 — 다른 문자가 새어 든다. 한 줄에 12개씩 적는다.
-- N 형식 비교: UNISTR 는 NVARCHAR2 라 VARCHAR 인 열·문자열과 섞이면 패턴이 DB 문자셋으로 바뀔 수 있다. 그래서 열은 `TO_NCHAR(열)`,
--   패턴 머리 `'[[:space:]'` 도 `TO_NCHAR(...)` 로 감싸 패턴 전체를 N 형식으로 둔다.
-- 길이 가드: description 은 VARCHAR2(65532) 라 TO_NCHAR 결과가 NVARCHAR2 길이 한도를 넘으면 마이그레이션이 오류로 멈출 수 있다.
--   API 가 설명을 2,000자로 제한(DescriptionLimits.MAX_LENGTH, 웹 MAX_DESCRIPTION_LENGTH)하므로 2,000자 이하만 정규식으로 보고,
--   더 긴 행(제한 전에 저장된 행)은 CASE 의 ELSE 로 글자가 있는 값처럼 남긴다 — AND 는 계산 순서를 보장하지 않아 CASE 로 막는다.
--   2,000자를 넘는 글자 없는 설명은 정리되지 않지만 읽기 가드가 계속 막는다.
-- 빈 값 비교: Tibero 는 `''` 를 NULL 로 보므로 `= ''` 은 한 행도 고르지 못한다. 잘라 낸 결과를 `IS NULL` 로 본다.
-- 암묵 커밋: DDL 없는 UPDATE 문장뿐이라 Tibero DDL 의 암묵 커밋과 무관하고, 실패하면 롤백된다.
--
-- 남은 위험:
--   (a) UNISTR·TO_NCHAR 를 REGEXP_REPLACE 안에서 Tibero 실서버로 돌려 보지 못했다(자격 증명 없음 — 텍스트 시험만).
--   (b) 결합 문자(Mn·Me — BMP 의 U+0300-036F·변이 선택자 U+FE00-FE0F 등과 보조 평면 전부)와 보조 평면 Cf 는 목록에 없다.
--       PG V172 는 `\p{Mn}\p{Me}` 로 이런 값만인 설명도 비우므로 두 DB 결과가 다를 수 있다 — 읽기 가드가 막는다.
UPDATE saved_query
   SET description = NULL
 WHERE description IS NOT NULL
   AND CASE
         WHEN LENGTH(description) <= 2000 THEN
           REGEXP_REPLACE(TO_NCHAR(description),
          TO_NCHAR('[[:space:]') ||
          UNISTR('\0001\0002\0003\0004\0005\0006\0007\0008\0009\000A\000B\000C') ||
          UNISTR('\000D\000E\000F\0010\0011\0012\0013\0014\0015\0016\0017\0018') ||
          UNISTR('\0019\001A\001B\001C\001D\001E\001F\007F\0080\0081\0082\0083') ||
          UNISTR('\0084\0085\0086\0087\0088\0089\008A\008B\008C\008D\008E\008F') ||
          UNISTR('\0090\0091\0092\0093\0094\0095\0096\0097\0098\0099\009A\009B') ||
          UNISTR('\009C\009D\009E\009F\00A0\00AD\0600\0601\0602\0603\0604\0605') ||
          UNISTR('\061C\06DD\070F\0890\0891\08E2\115F\1160\1680\180E\2000\2001') ||
          UNISTR('\2002\2003\2004\2005\2006\2007\2008\2009\200A\200B\200C\200D') ||
          UNISTR('\200E\200F\202A\202B\202C\202D\202E\202F\205F\2060\2061\2062') ||
          UNISTR('\2063\2064\2065\2066\2067\2068\2069\206A\206B\206C\206D\206E') ||
          UNISTR('\206F\2800\3000\3164\FEFF\FFA0\FFF0\FFF1\FFF2\FFF3\FFF4\FFF5') ||
          UNISTR('\FFF6\FFF7\FFF8\FFF9\FFFA\FFFB\2028\2029') ||
          ']', '')
         ELSE TO_NCHAR('x')
       END IS NULL;
UPDATE chart
   SET description = NULL
 WHERE description IS NOT NULL
   AND CASE
         WHEN LENGTH(description) <= 2000 THEN
           REGEXP_REPLACE(TO_NCHAR(description),
          TO_NCHAR('[[:space:]') ||
          UNISTR('\0001\0002\0003\0004\0005\0006\0007\0008\0009\000A\000B\000C') ||
          UNISTR('\000D\000E\000F\0010\0011\0012\0013\0014\0015\0016\0017\0018') ||
          UNISTR('\0019\001A\001B\001C\001D\001E\001F\007F\0080\0081\0082\0083') ||
          UNISTR('\0084\0085\0086\0087\0088\0089\008A\008B\008C\008D\008E\008F') ||
          UNISTR('\0090\0091\0092\0093\0094\0095\0096\0097\0098\0099\009A\009B') ||
          UNISTR('\009C\009D\009E\009F\00A0\00AD\0600\0601\0602\0603\0604\0605') ||
          UNISTR('\061C\06DD\070F\0890\0891\08E2\115F\1160\1680\180E\2000\2001') ||
          UNISTR('\2002\2003\2004\2005\2006\2007\2008\2009\200A\200B\200C\200D') ||
          UNISTR('\200E\200F\202A\202B\202C\202D\202E\202F\205F\2060\2061\2062') ||
          UNISTR('\2063\2064\2065\2066\2067\2068\2069\206A\206B\206C\206D\206E') ||
          UNISTR('\206F\2800\3000\3164\FEFF\FFA0\FFF0\FFF1\FFF2\FFF3\FFF4\FFF5') ||
          UNISTR('\FFF6\FFF7\FFF8\FFF9\FFFA\FFFB\2028\2029') ||
          ']', '')
         ELSE TO_NCHAR('x')
       END IS NULL;
UPDATE dashboard
   SET description = NULL
 WHERE description IS NOT NULL
   AND CASE
         WHEN LENGTH(description) <= 2000 THEN
           REGEXP_REPLACE(TO_NCHAR(description),
          TO_NCHAR('[[:space:]') ||
          UNISTR('\0001\0002\0003\0004\0005\0006\0007\0008\0009\000A\000B\000C') ||
          UNISTR('\000D\000E\000F\0010\0011\0012\0013\0014\0015\0016\0017\0018') ||
          UNISTR('\0019\001A\001B\001C\001D\001E\001F\007F\0080\0081\0082\0083') ||
          UNISTR('\0084\0085\0086\0087\0088\0089\008A\008B\008C\008D\008E\008F') ||
          UNISTR('\0090\0091\0092\0093\0094\0095\0096\0097\0098\0099\009A\009B') ||
          UNISTR('\009C\009D\009E\009F\00A0\00AD\0600\0601\0602\0603\0604\0605') ||
          UNISTR('\061C\06DD\070F\0890\0891\08E2\115F\1160\1680\180E\2000\2001') ||
          UNISTR('\2002\2003\2004\2005\2006\2007\2008\2009\200A\200B\200C\200D') ||
          UNISTR('\200E\200F\202A\202B\202C\202D\202E\202F\205F\2060\2061\2062') ||
          UNISTR('\2063\2064\2065\2066\2067\2068\2069\206A\206B\206C\206D\206E') ||
          UNISTR('\206F\2800\3000\3164\FEFF\FFA0\FFF0\FFF1\FFF2\FFF3\FFF4\FFF5') ||
          UNISTR('\FFF6\FFF7\FFF8\FFF9\FFFA\FFFB\2028\2029') ||
          ']', '')
         ELSE TO_NCHAR('x')
       END IS NULL;
UPDATE saved_query
   SET folder = NULL
 WHERE folder IS NOT NULL
   AND REGEXP_REPLACE(TO_NCHAR(folder),
          TO_NCHAR('[[:space:]') ||
          UNISTR('\0001\0002\0003\0004\0005\0006\0007\0008\0009\000A\000B\000C') ||
          UNISTR('\000D\000E\000F\0010\0011\0012\0013\0014\0015\0016\0017\0018') ||
          UNISTR('\0019\001A\001B\001C\001D\001E\001F\007F\0080\0081\0082\0083') ||
          UNISTR('\0084\0085\0086\0087\0088\0089\008A\008B\008C\008D\008E\008F') ||
          UNISTR('\0090\0091\0092\0093\0094\0095\0096\0097\0098\0099\009A\009B') ||
          UNISTR('\009C\009D\009E\009F\00A0\00AD\0600\0601\0602\0603\0604\0605') ||
          UNISTR('\061C\06DD\070F\0890\0891\08E2\115F\1160\1680\180E\2000\2001') ||
          UNISTR('\2002\2003\2004\2005\2006\2007\2008\2009\200A\200B\200C\200D') ||
          UNISTR('\200E\200F\202A\202B\202C\202D\202E\202F\205F\2060\2061\2062') ||
          UNISTR('\2063\2064\2065\2066\2067\2068\2069\206A\206B\206C\206D\206E') ||
          UNISTR('\206F\2800\3000\3164\FEFF\FFA0\FFF0\FFF1\FFF2\FFF3\FFF4\FFF5') ||
          UNISTR('\FFF6\FFF7\FFF8\FFF9\FFFA\FFFB\2028\2029') ||
          ']', '') IS NULL;
