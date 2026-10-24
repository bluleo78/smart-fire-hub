-- V145: 스마트 작업 실행(리포트)의 보안 등급 security_level_id (WD-198).
--
-- 왜 필요한가: 리포트 전문(실행 상세·HTML·PDF)은 작업 작성자만 열 수 있지만(WD-162), 열 때 작성자의 보안 등급을 다시 보지 않았다.
-- 작성자 역할의 max_security_level 을 낮춘 뒤에도 예전에 높은 등급 데이터를 읽고 만든 리포트가 그대로 열렸다. 리포트에는 등급 칸이
-- 없어 판정할 근거가 없었으므로, 실행 동안 읽은 데이터셋 중 「가장 높은 등급」을 실행 행에 남긴다.
--
-- nullable 인 이유: 이 컬럼 이전의 리포트와 등급 데이터를 읽지 않은 리포트는 등급이 없다(null = 지금처럼 열림 — 사람이 고른 설계 D).
-- DEFAULT 를 두지 않는다 — 기본 등급으로 채우면 「등급 데이터를 읽지 않음」과 「기본 등급 데이터를 읽음」이 구분되지 않는다.
--
-- FK 는 dataset.security_level_id 와 같은 (tenant_id, security_level_id) 복합 키다 — 다른 테넌트의 등급을 가리키는 행을 DB 가 거부한다.
-- 등급 삭제는 SecurityLevelService.delete 가 이 컬럼도 「사용 중」으로 세어 옮길 등급으로 함께 옮긴다(FK 위반 500 방지).
-- RLS: proactive_job_execution 은 V104 에서 이미 테넌트 정책이 걸려 있어 컬럼 추가만으로 그대로 적용된다.
ALTER TABLE proactive_job_execution ADD COLUMN IF NOT EXISTS security_level_id BIGINT;

ALTER TABLE proactive_job_execution
  ADD CONSTRAINT fk_proactive_job_execution_security_level
  FOREIGN KEY (tenant_id, security_level_id) REFERENCES security_level (tenant_id, id);

-- 등급 삭제 시 사용 중 개수 집계·일괄 이동이 이 컬럼으로 거른다.
CREATE INDEX IF NOT EXISTS idx_proactive_job_execution_security_level
  ON proactive_job_execution (security_level_id);
