-- V146: 「진행 중 작업은 자원당 1개」 부분 유니크 인덱스에서 데이터 내보내기(DATA_EXPORT)를 뺀다 (WD-247).
--
-- 왜 필요한가: idx_async_job_active_unique(V14 원본, V93 에서 tenant_id 를 앞에 붙여 다시 만듦)는 가져오기(IMPORT)가
-- 같은 데이터셋에 동시에 두 번 적재되지 않도록 「데이터셋당 진행 중 1개」를 원자적으로 막으려고 만든 것이다. 그런데
-- 조건이 job_type 을 가리지 않아 DATA_EXPORT 에도 걸렸다. 그 결과 5만 행 초과 데이터셋을 누군가 비동기 내보내기 하는
-- 동안 다른 사용자(또는 같은 사용자의 다른 형식) 내보내기가 409 「이미 같은 값이 있어 저장할 수 없습니다」로 실패했다.
-- 내보내기는 읽기 작업이라 같은 데이터셋에 여러 개가 동시에 돌아도 문제가 없고, 서버 규칙은 「사용자당 동시 내보내기
-- 3개까지」(DataExportService.checkRateLimit)뿐이다.
--
-- 바꾸는 것: WHERE 절에 job_type <> 'DATA_EXPORT' 만 더한다. 키 컬럼(tenant_id, job_type, resource, resource_id)과
-- 「진행 중」 조건(stage NOT IN ('COMPLETED', 'FAILED'))은 V93 그대로다 — IMPORT·API_CONNECTION_REFRESH_ALL 등
-- 다른 작업 종류는 지금처럼 자원당 진행 중 1개로 막힌다. 인덱스 이름도 유지한다(DataImportService 가 이 이름으로
-- 중복 임포트 오류를 알아본다).
--
-- 운영 영향: 조건을 좁히는 것이라 기존 행 때문에 생성이 실패할 수 없다(현재 유일한 집합의 부분집합). async_job 은
-- 정리 작업이 오래된 행을 지우는 작은 테이블이라 CONCURRENTLY 없이 트랜잭션 안에서 다시 만든다 — DROP~CREATE 사이에
-- 유니크 공백이 생기지 않도록 한 트랜잭션에 두는 편이 낫다. 재생성 동안 async_job 쓰기만 잠깐 대기한다.
-- RLS·jOOQ: 인덱스만 바꾸므로 정책·생성 소스에는 영향이 없다.
DROP INDEX IF EXISTS idx_async_job_active_unique;
CREATE UNIQUE INDEX idx_async_job_active_unique
  ON async_job (tenant_id, job_type, resource, resource_id)
  WHERE stage NOT IN ('COMPLETED', 'FAILED') AND job_type <> 'DATA_EXPORT';
