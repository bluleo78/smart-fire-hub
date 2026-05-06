-- pipeline_execution.trigger_id FK를 ON DELETE SET NULL로 변경
-- 트리거 삭제 시 실행 이력의 trigger_id를 NULL로 설정하여 FK 위반 방지
ALTER TABLE pipeline_execution
    DROP CONSTRAINT IF EXISTS pipeline_execution_trigger_id_fkey;

ALTER TABLE pipeline_execution
    ADD CONSTRAINT pipeline_execution_trigger_id_fkey
        FOREIGN KEY (trigger_id) REFERENCES pipeline_trigger(id) ON DELETE SET NULL;
