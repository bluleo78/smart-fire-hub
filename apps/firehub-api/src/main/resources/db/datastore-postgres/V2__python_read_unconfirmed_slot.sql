-- V2: PYTHON 읽기 슬롯 세션 끊기 미확인 표식(WD-701 4차) — 플랫폼 db/migration V159 와 같은 정의(분리 설치의 데이터 저장소 쪽).
--
-- 왜 DB 인가: 표식은 예전에 API 프로세스 메모리에만 있어, 롤링 업데이트 중 다른 인스턴스의 실행 준비(JIT)는 표식을 보지 못하고 옛 세션이
-- 살아 있는 슬롯을 넓힐 수 있었다. 이제 모든 인스턴스가 같은 행을 본다.
--
-- 왜 데이터 저장소 객체인가(플랫폼 메타데이터가 아니라): 표식을 읽고 쓰는 쪽(PythonReadGrantSync)은 언제나 PG 데이터 저장소와만 말하고, 비교 대상인
-- pg_stat_activity.backend_start 도 그 서버의 시계다 — marked_at 을 같은 서버의 clock_timestamp() 로 적어야 시계 차이가 없다. 플랫폼이
-- Tibero 인 설치본(데이터 저장소는 늘 따로)에서도 PG SQL 그대로 돈다. 분리 설치는 이 기준선 다음 판(V2)이 데이터 저장소에 만든다.
--
-- 한 행 = (테넌트, 슬롯, 종류).
--   kind REDEFINED     — 등급 정의 변경(슬롯 k 의 뜻이 바뀜). 실행 준비는 그 슬롯의 세션이 하나도 없을 때만 넓힌다.
--   kind STALE_SESSION — 데이터셋 좁히기 회수 미확인·회수 뒤 끊기 미확인. 실행 준비는 backend_start < marked_at 인(표식 전에 시작한) 세션만 끊는다.
--   kind REDEFINED_APPLIED — 커밋 뒤 실행 준비가 REDEFINED 슬롯을 확인·넓힌 뒤 남기는 흔적. 실행 준비는 무시하고, 등급 정의 변경 실행기 작업이
--                       그 슬롯을 다시 확인·끊는다(커밋 직전 옛 정의로 준비한 스크립트가 그 뒤에 늦게 붙었을 수 있다). 작업이 적용하면 지운다.
-- marked_at   : 표식 시각(데이터 저장소 시계). 다시 달면 더 늦은 값으로 미룬다.
-- mark_version: 달 때마다 1씩 오른다 — 실행 준비가 읽은 행만 정확히 지우게 한다(그 사이 다른 인스턴스가 다시 단 표식을 지우지 않게).
-- tenant FK 를 두지 않는다 — 분리 설치의 데이터 저장소에는 tenant 테이블이 없고, 두 정의를 같게 둔다(DataStoreBaselineParityTest).
-- RLS 를 두지 않는다 — 커밋 콜백·실행기 버림 콜백처럼 테넌트 컨텍스트가 없을 수 있는 곳에서 쓰고, 행에는 슬롯 번호·시각뿐이다. 모든 질의가 tenant_id 를
-- 명시한다(TenantSchemaConformanceTest 허용 목록).
CREATE TABLE IF NOT EXISTS public.python_read_unconfirmed_slot (
  tenant_id    BIGINT       NOT NULL,
  slot         SMALLINT     NOT NULL CHECK (slot BETWEEN 1 AND 10),
  kind         VARCHAR(24)  NOT NULL CHECK (kind IN ('REDEFINED', 'REDEFINED_APPLIED', 'STALE_SESSION')),
  marked_at    TIMESTAMPTZ  NOT NULL,
  mark_version BIGINT       NOT NULL DEFAULT 1,
  PRIMARY KEY (tenant_id, slot, kind)
);

REVOKE ALL ON TABLE public.python_read_unconfirmed_slot FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.python_read_unconfirmed_slot TO app_tenant;

COMMENT ON TABLE public.python_read_unconfirmed_slot IS
  'PYTHON 읽기 슬롯 세션 끊기 미확인 표식 — 모든 API 인스턴스의 실행 준비가 넓히기 전에 본다(WD-701 4차)';
COMMENT ON COLUMN public.python_read_unconfirmed_slot.kind IS
  'REDEFINED=등급 정의 변경(세션 전부 확인) / REDEFINED_APPLIED=실행 준비가 넓힌 뒤 실행기 작업이 다시 확인할 슬롯 / STALE_SESSION=회수·끊기 미확인(표식 전 세션만)';
COMMENT ON COLUMN public.python_read_unconfirmed_slot.marked_at IS
  '표식 시각(데이터 저장소 clock_timestamp) — pg_stat_activity.backend_start 와 비교한다';
COMMENT ON COLUMN public.python_read_unconfirmed_slot.mark_version IS
  '달 때마다 오르는 번호 — 실행 준비는 읽은 번호의 행만 지운다';

-- 표식 시각 전에 시작한 슬롯 롤 세션 수(WD-701 4차 리뷰 2). 런타임 롤(app_tenant)은 슬롯 롤의 멤버도 pg_read_all_stats 멤버도 아니라
-- pg_stat_activity 에서 남의 세션 backend_start 가 NULL 로 보인다(실측) — 그대로 비교하면 "표식 전 세션 없음" 으로 오판한다(fail-open).
-- 그래서 소유자 권한(SECURITY DEFINER — 소유자는 superuser 또는 pg_read_all_stats)으로 센다. 소유자 풀(크기 1, 끊기가 씀)을 잠금 안에서
-- 기다리지 않으려고 연결이 아니라 함수로 둔다. 슬롯 롤 이름(pipeline_py_t*_s*)만 센다(최소 권한). 그래도 시작 시각이 안 보이면 표식 전으로 센다(fail-closed).
CREATE OR REPLACE FUNCTION public.fh_python_read_sessions_before(role_names text[], cutoffs timestamptz[])
RETURNS bigint
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  n bigint;
BEGIN
  -- 배열이 NULL 이거나 길이가 다르면 unnest 가 짝을 NULL 로 채워 "기준 없음/롤 없음" 으로 잘못 셀 수 있다 — 세지 않고 오류로 끝낸다.
  -- 호출자(잠금 안 재확인)는 오류를 실행 거부·작업 재시도로 다룬다(fail-closed).
  IF role_names IS NULL OR cutoffs IS NULL
     OR pg_catalog.cardinality(role_names) <> pg_catalog.cardinality(cutoffs) THEN
    RAISE EXCEPTION 'fh_python_read_sessions_before: role_names/cutoffs 가 NULL 이거나 길이가 다르다'
      USING ERRCODE = '22023';
  END IF;
  -- 여러 인자 unnest 는 FROM 절 문법이라 스키마를 붙일 수 없다(search_path 가 pg_catalog 로 고정돼 있어 안전).
  SELECT pg_catalog.count(*) INTO n
  FROM pg_catalog.pg_stat_activity a
  JOIN unnest(role_names, cutoffs) AS t(role_name, cutoff) ON a.usename::text = t.role_name
  WHERE t.role_name LIKE 'pipeline\_py\_t%\_s%'
    AND (t.cutoff IS NULL OR a.backend_start IS NULL OR a.backend_start < t.cutoff);
  RETURN n;
END
$$;

REVOKE ALL ON FUNCTION public.fh_python_read_sessions_before(text[], timestamptz[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fh_python_read_sessions_before(text[], timestamptz[]) TO app_tenant;
