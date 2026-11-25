-- V168: api_connection.name 옛 행의 앞뒤 공백 정리 (WD-1107)
--
-- 왜: 이름 중복 검사가 lower(trim(name)) 비교로 바뀌었다. trim() 은 ASCII 공백만 걷으므로, NBSP·전각 공백·탭 등 유니코드
-- White_Space 로 감싼 옛 행은 검사에서 빗나간다. 서비스가 새 이름을 정규화(strip)해 저장하는 것과 맞추려고 옛 행도 한 번 정리한다.
--
-- 걷는 공백 집합은 TextRules.strip((?U)\s) 과 같은 유니코드 White_Space 다.
-- 걷는 문자(앞뒤 White_Space): U+0009-000D, 0020, 0085, 00A0, 1680, 2000-200A, 2028, 2029, 202F, 205F, 3000.
-- PG 정규식 괄호식의 \uXXXX 는 문자열 리터럴이 아니라 정규식 엔진이 해석한다(standard_conforming_strings=on).
--
-- 유니크 인덱스 idx_api_connection_tenant_name(V119, tenant_id+name 정확 일치)은 건드리지 않고 어기지도 않는다.
--   - 같은 테넌트에서 정리한 이름이 같아지는 행이 여럿이면, 이미 정확히 그 이름인 행이 있으면 그 행이 우선이고, 없으면 id 가 가장
--     작은 한 행만 정리한다. 나머지는 그대로 둔다(건너뜀).
--   - 정리 결과가 빈 문자열(공백뿐인 이름)이면 건드리지 않는다.
-- updated_at 은 사용자 변경이 아닌 데이터 정리라 건드리지 않는다.
-- RLS: Flyway 는 소유자 롤로 돌아 모든 테넌트의 행이 보이므로 tenant_id 로 묶어 처리한다.
WITH s AS (
  SELECT id, tenant_id, name,
         regexp_replace(
           name,
           '^[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+|[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$',
           '', 'g') AS stripped
  FROM api_connection
), ranked AS (
  SELECT s.*, row_number() OVER (PARTITION BY tenant_id, stripped
                                 ORDER BY (name = stripped) DESC, id) AS rn
  FROM s
)
UPDATE api_connection a SET name = r.stripped
FROM ranked r
WHERE a.id = r.id AND r.rn = 1 AND r.name <> r.stripped AND r.stripped <> '';
