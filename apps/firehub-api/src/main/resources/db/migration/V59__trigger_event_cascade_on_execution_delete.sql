-- V59__trigger_event_cascade_on_execution_delete.sql
-- pipeline_execution TTL 정리 시 자식 trigger_event 행이 자동 삭제되도록
-- trigger_event.execution_id FK 를 ON DELETE CASCADE 로 변경 (#223)

ALTER TABLE trigger_event
    DROP CONSTRAINT IF EXISTS trigger_event_execution_id_fkey;

ALTER TABLE trigger_event
    ADD CONSTRAINT trigger_event_execution_id_fkey
        FOREIGN KEY (execution_id) REFERENCES pipeline_execution(id) ON DELETE CASCADE;
