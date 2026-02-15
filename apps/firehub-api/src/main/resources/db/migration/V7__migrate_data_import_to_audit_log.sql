-- 기존 data_import → audit_log 마이그레이션 (완료/실패 건만)
INSERT INTO audit_log (user_id, username, action_type, resource, resource_id,
                       description, action_time, result, metadata)
SELECT
    di.imported_by, u.name, 'IMPORT', 'dataset',
    CAST(di.dataset_id AS VARCHAR),
    '파일 임포트: ' || di.file_name,
    COALESCE(di.completed_at, di.created_at),
    CASE di.status WHEN 'COMPLETED' THEN 'SUCCESS' WHEN 'FAILED' THEN 'FAILURE' ELSE 'SUCCESS' END,
    jsonb_build_object(
        'fileName', di.file_name, 'fileSize', di.file_size, 'fileType', di.file_type,
        'totalRows', di.total_rows, 'successRows', di.success_rows,
        'errorRows', di.error_rows, 'errorDetails', di.error_details
    )
FROM data_import di JOIN "user" u ON u.id = di.imported_by
WHERE di.status IN ('COMPLETED', 'FAILED');

-- data_import 테이블 삭제
DROP TABLE data_import;
