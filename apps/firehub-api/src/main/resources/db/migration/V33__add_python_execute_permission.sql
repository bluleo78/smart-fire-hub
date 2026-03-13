-- python_execute 권한 추가 (기본적으로 어떤 역할에도 부여되지 않음)
INSERT INTO permission (code, description, category)
VALUES ('pipeline:python_execute', '파이프라인 Python 스크립트 실행 (Phase 2 nsjail 전까지 호스트 프로세스 환경 접근 가능)', 'pipeline')
ON CONFLICT (code) DO NOTHING;

-- 참고: 이 권한은 의도적으로 USER 또는 ADMIN 역할에 추가하지 않음.
-- 관리자가 역할 관리 UI를 통해 명시적으로 부여해야 함.
