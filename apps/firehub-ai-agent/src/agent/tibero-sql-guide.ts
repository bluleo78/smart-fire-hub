/**
 * Tibero 테넌트용 SQL 작성 규칙(WD-79).
 *
 * <p><b>왜 한 곳인가.</b> 메인 채팅(SYSTEM_PROMPT)·opencode(OPENCODE_SYSTEM_PROMPT)·Proactive 리포트 프롬프트·
 * data-analyst·pipeline-builder 가 모두 같은 Tibero 규칙으로 SQL 을 써야 한다. 경로마다 따로 적으면 한쪽만 고쳐져
 * 모델이 경로에 따라 다른 문법을 쓰게 된다. 서브에이전트 지식 파일(md)은 상수를 import 할 수 없으므로 이 문구를
 * 그대로 옮겨 적고, 각 서브에이전트 safeguard 테스트가 "md 에 이 상수가 그대로 들어 있는지" 를 고정한다.
 *
 * <p><b>예시 SQL 은 실서버에서 확인한 구문만 쓴다</b>(Tibero 7, 2026-10-24 — TRUNC·ADD_MONTHS·SYSDATE·CAST·ROUND·
 * NVL·COALESCE·UPPER LIKE·FETCH FIRST·ROWNUM·윈도 함수·날짜 리터럴). `::` 는 JDBC-8006, `DATE_TRUNC` 는 JDBC-8036 으로
 * 실패함을 확인했다. 상위 N 은 FETCH FIRST 를 기본으로 가르친다(Ruling 5 — LIMIT 도 실행되지만 한 가지 형식으로 통일).
 *
 * <p><b>오류 문구</b>는 api 가 실제로 돌려주는 것을 그대로 인용한다 — 분석 조회는
 * `PipelineSqlErrorMessages.describeTiberoQueryError`(테이블 없음 안내 / 시간 초과 / "Tibero 오류: " + 원문),
 * 검증 거부는 `SqlValidator`(스키마 없는 테이블 참조·허용 목록 밖 함수). 오류 코드는 api·실서버 확인 목록에 있는
 * 것만 적는다(-8033 테이블 없음·권한 없음, -8006 구문, -8036 함수 없음).
 *
 * <p>행 검색 도구는 Tibero 테넌트에서 숨겨지므로(Task 8) 도구 이름을 적지 않는다 — 이름을 보이면 없는 도구를 부른다.
 * PG 테넌트 프롬프트에는 이 문구가 절대 들어가지 않는다(바이트 동일 테스트로 고정).
 */
export const TIBERO_SQL_GUIDE = `**[Tibero SQL 규칙 — 이 테넌트의 데이터 저장소]**
이 테넌트의 데이터셋 테이블은 PostgreSQL 이 아니라 Tibero(Oracle 계열)에 있다. 분석 SQL·파이프라인 SQL 모두 아래 문법으로 쓴다.
1. 테이블은 스키마로 한정한다: \`get_data_schema\` 응답의 \`schema\` 값(예: \`GENIA_T5\`)을 붙여 \`genia_t5.sales\` 처럼 쓴다(대소문자는 상관없다). 한정하지 않으면 api 가 400 으로 거부한다("테이블 참조에 스키마가 없습니다: … 처럼 스키마와 함께 써 주세요."). 예외는 상수 조회용 \`DUAL\` 하나다(\`SELECT SYSDATE FROM DUAL\`).
2. 테이블·컬럼 이름에 큰따옴표를 씌우지 않는다: \`genia_t5.sales\`, \`region\` 처럼 그대로 쓴다. Tibero 는 따옴표 없는 이름을 대문자로 읽으므로, 소문자 이름을 큰따옴표로 감싸면 다른(없는) 이름이 된다. 테이블 별칭에는 AS 를 붙이지 않는다(\`FROM genia_t5.sales s\`). 열 별칭은 영문으로 짓고 예약어(DATE·LEVEL·SIZE·USER·COMMENT 등)는 피한다.
3. 형변환은 \`CAST(x AS NUMBER)\`·\`TO_NUMBER(x)\`·\`TO_CHAR(d, 'YYYY-MM')\`·\`TO_DATE\` 로 한다. 날짜 상수는 \`DATE '2026-01-15'\`·\`TIMESTAMP '2026-01-15 10:00:00'\`. PostgreSQL 식 \`::\` 형변환은 쓰지 않는다(JDBC-8006 오류).
4. 날짜 묶기: 월 \`TRUNC(d, 'MM')\` · 주 \`TRUNC(d, 'IW')\`(ISO 주, 월요일 시작) · 일 \`TRUNC(d, 'DD')\` · 년 \`TRUNC(d, 'YYYY')\`. 기간: \`ADD_MONTHS(TRUNC(SYSDATE), -3)\`(3개월 전 0시) · \`SYSDATE - 7\`(7일 전). 부분 추출: \`EXTRACT(YEAR FROM d)\` · \`TO_CHAR(d, 'HH24')\`.
   - \`DATE_TRUNC\`·\`INTERVAL '1 month'\`·\`NOW()\` 는 쓰지 않는다(\`DATE_TRUNC\` 는 JDBC-8036 오류).
5. 상위 N 개: \`ORDER BY total DESC FETCH FIRST 10 ROWS ONLY\`. 대안은 \`SELECT * FROM (SELECT … ORDER BY total DESC) WHERE ROWNUM <= 10\`. \`LIMIT\` 은 쓰지 않는다. 결과 행 수는 api 가 자동으로 제한하므로(\`maxRows\`) 집계 결과에 행 제한을 따로 붙일 필요는 없다.
6. 대소문자 무시 검색은 \`UPPER(name) LIKE UPPER('%seo%')\`, 문자열 연결은 \`'a' || 'b'\`. \`ILIKE\` 는 쓰지 않는다.
7. 나눗셈은 정수끼리도 소수가 나온다(\`7/3\` → 2.33…) — 형변환 없이 \`ROUND(x, 2)\` 로 자른다. NULL 대체는 \`NVL(x, 0)\`·\`COALESCE(x, 0)\`.
8. \`GROUP BY\` 에는 열 별칭이나 위치 번호를 쓰지 않고 식을 그대로 다시 쓴다(\`SELECT TRUNC(d, 'MM') AS mon, COUNT(*) AS cnt … GROUP BY TRUNC(d, 'MM') ORDER BY 1\`) — \`GROUP BY 1\` 은 상수 1 로 묶는다는 뜻이 된다. \`ORDER BY\` 는 별칭·위치 번호를 써도 된다.
9. \`WITH … SELECT\`(CTE)와 윈도 함수 \`LAG(x) OVER (ORDER BY x)\`·\`SUM(x) OVER ()\`·\`RANK() OVER (ORDER BY x DESC)\` 는 그대로 쓴다. 월별 증감은 CTE 에서 월별로 집계한 뒤 바깥 SELECT 에서 \`LAG(total) OVER (ORDER BY mon)\` 처럼 CTE 의 열로 계산한다(같은 SELECT 의 별칭은 OVER 안에서 쓰지 않는다).
10. 이 테넌트에서 쓸 수 없는 것: 공간 함수(\`ST_*\`)·공간 컬럼, 행 검색(내용 검색) 도구, \`jsonb\`·\`generate_series\` 같은 PostgreSQL 전용 기능.
11. 오류 자기 교정 — PostgreSQL 오류 상태 코드 규칙은 이 테넌트에 맞지 않는다. api 가 준 안내 문구와 음수 오류 코드(\`JDBC-NNNN:\` 원문)를 읽고 1턴 안에 고친다. 같은 SQL 을 그대로 다시 실행하지 않는다.
   - "SQL 에서 참조한 테이블이 없습니다. 테이블 이름과 스키마 한정자를 확인하세요." (원인 \`JDBC-8033\`) → \`get_data_schema\` 의 \`schema\`·\`tableName\` 과 대조해 고친다. 권한이 없는 테이블도 같은 코드로 온다 — 스키마 한정자를 지우지 않는다.
   - "Tibero 오류: JDBC-8006:…"(구문 오류, 예: Missing FROM keyword) → \`::\` 같은 PostgreSQL 문법이 남았는지, 상수 조회에 \`FROM DUAL\` 을 빠뜨렸는지 본다.
   - "Tibero 오류: JDBC-8036:…"(함수 없음) → PostgreSQL 전용 함수를 위 규칙의 함수로 바꾼다(예: 월 묶기는 \`TRUNC(d, 'MM')\`).
   - "허용되지 않는 함수 호출: '…'. 알려진 안전 함수 목록에 없습니다." → 위 규칙에 나온 함수로 바꾼다.
   - "테이블 참조에 스키마가 없습니다: …" → 1번 규칙대로 \`schema\` 를 붙인다.
   - "실행 시간 제한(30초)을 넘어 중단했습니다." → 기간·조건을 좁히거나 집계 단위를 키운다.
`;
