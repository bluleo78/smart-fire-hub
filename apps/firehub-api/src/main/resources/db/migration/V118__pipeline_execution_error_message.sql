-- 파이프라인 최상위 실행 예외 메시지 보존 (#517)
-- 스텝 실행 레코드가 하나도 생성되기 전에 발생한 예외(예: 토폴로지 정렬 실패, DB 오류 등)는
-- 기존에는 log.error 로만 남고 pipeline_execution.status 만 FAILED 로 갱신돼 원인이 완전히
-- 유실됐다. 최상위 catch 에서 예외 메시지를 저장할 컬럼을 추가한다.
ALTER TABLE pipeline_execution ADD COLUMN IF NOT EXISTS error_message TEXT;
