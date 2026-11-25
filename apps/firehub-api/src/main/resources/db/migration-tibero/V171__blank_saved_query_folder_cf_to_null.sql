-- V171: saved_query.folder 중 V170 이 빠뜨린 BMP Cf·채움 문자만인 값도 NULL 로 비운다 (WD-1144) — PG V171 과 짝(MigrationParityTest).
--
-- 무엇을: V170 은 NBSP·전각 공백 등 일부만 걷었다. 이 파일은 BMP Cf·채움 문자(미할당 포함, TextRules 비가시 목록)까지 걷어
--   그런 문자만인 folder 도 NULL 로 만든다. 이미 V170 이 비운 행은 NULL 이라 다시 걸리지 않으므로 다시 돌려도 결과가 같다(멱등).
-- 왜 새 버전인가: V170 은 이미 적용됐을 수 있어 파일을 고치면 체크섬이 달라져 기동이 멈춘다(forward-only). 그래서 V170 은 그대로 두고 여기서 잇는다.
-- Tibero 는 정규식에 Unicode 속성(`\p{Cf}`)이 없어 알려진 코드점 목록으로 같은 일을 한다. 목록이 TextRules 보다 좁아 남는 잔여 행은
--   읽기 가드(`TextRules.hasText`·웹 `hasVisibleText`)가 계속 막는다.
--
-- UNISTR 로 쓴 까닭: 눈에 안 보이는 실자는 에디터·복사가 지우거나 바꿀 수 있어 파일에는 ASCII 만 두고 `UNISTR('\XXXX…')` 로 코드점을 하나씩 적는다.
--   범위(`-`)는 쓰지 않는다 — 다른 문자가 새어 든다.
-- N 형식 비교: UNISTR 는 NVARCHAR2 라 VARCHAR 인 folder 와 섞이면 패턴이 DB 문자셋으로 바뀐다. 그래서 `TO_NCHAR(folder)` 로 맞춘다.
-- 빈 값 비교: Tibero 는 `''` 를 NULL 로 보므로 `= ''` 은 한 행도 고르지 못한다. 잘라 낸 결과를 `IS NULL` 로 본다.
-- 암묵 커밋: DDL 없는 UPDATE 한 문장이라 Tibero DDL 의 암묵 커밋과 무관하고, 실패하면 롤백된다.
--
-- 뺀 것: 줄바꿈 문자(U+0085·U+2028·U+2029)는 폴더 이름에 올 수 없고, Cc·Mn·Me 와 보조 평면 Cf 는 BMP 목록에 못 담는다.
-- 남은 위험:
--   (a) 옛 V170 은 실자 패턴이라 비유니코드 DB 문자셋에서 '?' 로 바뀌어 '?'·공백만인 폴더를 비웠을 수 있다 — 영향은 '?' 만으로 된 폴더 이름뿐.
--   (b) UNISTR·TO_NCHAR 를 REGEXP_REPLACE 안에서 Tibero 실서버로 돌려 보지 못했다(자격 증명 없음 — 텍스트 시험만).
UPDATE saved_query
   SET folder = NULL
 WHERE folder IS NOT NULL
   AND REGEXP_REPLACE(TO_NCHAR(folder),
          '[[:space:]' ||
          UNISTR('\00A0\00AD\0600\0601\0602\0603\0604\0605\061C\06DD\070F\0890') ||
          UNISTR('\0891\08E2\115F\1160\1680\180E\2000\2001\2002\2003\2004\2005') ||
          UNISTR('\2006\2007\2008\2009\200A\200B\200C\200D\200E\200F\202A\202B') ||
          UNISTR('\202C\202D\202E\202F\205F\2060\2061\2062\2063\2064\2065\2066') ||
          UNISTR('\2067\2068\2069\206A\206B\206C\206D\206E\206F\2800\3000\3164') ||
          UNISTR('\FEFF\FFA0\FFF0\FFF1\FFF2\FFF3\FFF4\FFF5\FFF6\FFF7\FFF8\FFF9') ||
          UNISTR('\FFFA\FFFB') || ']', '') IS NULL;
