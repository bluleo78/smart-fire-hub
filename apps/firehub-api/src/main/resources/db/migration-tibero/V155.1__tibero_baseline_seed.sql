-- Tibero 플랫폼 DB 기준선 — 시드 행(WD-490). PG V1~V155 가 넣는 참조 데이터와 같다(권한·기본 역할·기본 테넌트·보안 등급·카테고리·리포트 양식·
-- 온톨로지 예시). DDL 과 섞지 않으려고 V155 에서 나눴다 — DDL 이 암묵 커밋하므로 섞으면 실패 지점에 따라 일부 행만 남는다.
-- ID 는 PG 와 같은 값을 명시로 넣고(코드·다른 시드가 ID 로 참조), V155 의 IDENTITY 는 그 다음부터 시작한다.
-- 시각 열은 넣지 않는다 — PG 와 마찬가지로 적용 시각(DB 기본값)이 들어간다. boolean 은 0/1.

-- tenant (1행)
INSERT INTO tenant (id, slug, name, status) VALUES (1, 'default', 'Default Workspace', 'ACTIVE');

-- security_level (4행)
INSERT INTO security_level (id, tenant_id, rank, name, is_default, allowlist_required, admin_bypass, export_policy, ai_policy, share_policy, audit_access, created_by, updated_by) VALUES (1, 1, 1, '공개', 0, 0, 0, 'ALLOW', 'ALL', 'ALLOW', 0, NULL, NULL);
INSERT INTO security_level (id, tenant_id, rank, name, is_default, allowlist_required, admin_bypass, export_policy, ai_policy, share_policy, audit_access, created_by, updated_by) VALUES (2, 1, 2, '내부', 1, 0, 0, 'ALLOW', 'ALL', 'ALLOW', 0, NULL, NULL);
INSERT INTO security_level (id, tenant_id, rank, name, is_default, allowlist_required, admin_bypass, export_policy, ai_policy, share_policy, audit_access, created_by, updated_by) VALUES (3, 1, 3, '민감', 0, 0, 0, 'PERMISSION', 'SELF_HOSTED_ONLY', 'ALLOW', 1, NULL, NULL);
INSERT INTO security_level (id, tenant_id, rank, name, is_default, allowlist_required, admin_bypass, export_policy, ai_policy, share_policy, audit_access, created_by, updated_by) VALUES (4, 1, 4, '기밀', 0, 1, 0, 'DENY', 'SELF_HOSTED_ONLY', 'DENY', 1, NULL, NULL);

-- permission (47행)
INSERT INTO permission (id, code, description, category) VALUES (1, 'user:read', '사용자 목록 조회', 'user');
INSERT INTO permission (id, code, description, category) VALUES (3, 'user:write:self', '본인 프로필 수정', 'user');
INSERT INTO permission (id, code, description, category) VALUES (4, 'user:write', '사용자 정보 수정', 'user');
INSERT INTO permission (id, code, description, category) VALUES (6, 'role:read', '역할 목록 조회', 'role');
INSERT INTO permission (id, code, description, category) VALUES (7, 'role:write', '역할 생성/수정', 'role');
INSERT INTO permission (id, code, description, category) VALUES (8, 'role:delete', '역할 삭제', 'role');
INSERT INTO permission (id, code, description, category) VALUES (9, 'role:assign', '사용자에 역할 할당', 'role');
INSERT INTO permission (id, code, description, category) VALUES (10, 'permission:read', '권한 목록 조회', 'permission');
INSERT INTO permission (id, code, description, category) VALUES (11, 'dataset:read', '데이터셋 조회', 'dataset');
INSERT INTO permission (id, code, description, category) VALUES (12, 'dataset:write', '데이터셋 생성/수정', 'dataset');
INSERT INTO permission (id, code, description, category) VALUES (13, 'dataset:delete', '데이터셋 삭제', 'dataset');
INSERT INTO permission (id, code, description, category) VALUES (14, 'data:read', '데이터 행 조회', 'data');
INSERT INTO permission (id, code, description, category) VALUES (15, 'data:import', '데이터 임포트', 'data');
INSERT INTO permission (id, code, description, category) VALUES (16, 'data:export', '데이터 내보내기', 'data');
INSERT INTO permission (id, code, description, category) VALUES (17, 'pipeline:read', '파이프라인 조회', 'pipeline');
INSERT INTO permission (id, code, description, category) VALUES (18, 'pipeline:write', '파이프라인 생성/수정', 'pipeline');
INSERT INTO permission (id, code, description, category) VALUES (19, 'pipeline:execute', '파이프라인 실행', 'pipeline');
INSERT INTO permission (id, code, description, category) VALUES (20, 'pipeline:delete', '파이프라인 삭제', 'pipeline');
INSERT INTO permission (id, code, description, category) VALUES (21, 'audit:read', '감사 로그 조회', 'audit');
INSERT INTO permission (id, code, description, category) VALUES (24, 'ai:read', 'AI 세션 조회', 'ai');
INSERT INTO permission (id, code, description, category) VALUES (25, 'ai:write', 'AI 채팅 및 세션 관리', 'ai');
INSERT INTO permission (id, code, description, category) VALUES (26, 'ai:settings', 'AI 에이전트 설정 변경', 'ai');
INSERT INTO permission (id, code, description, category) VALUES (27, 'data:delete', '데이터셋 행 삭제', 'data');
INSERT INTO permission (id, code, description, category) VALUES (28, 'trigger:read', '트리거 조회', 'trigger');
INSERT INTO permission (id, code, description, category) VALUES (29, 'trigger:write', '트리거 생성/수정', 'trigger');
INSERT INTO permission (id, code, description, category) VALUES (30, 'trigger:delete', '트리거 삭제', 'trigger');
INSERT INTO permission (id, code, description, category) VALUES (31, 'apiconnection:read', 'API 연결 설정을 조회할 수 있습니다', 'apiconnection');
INSERT INTO permission (id, code, description, category) VALUES (32, 'apiconnection:write', 'API 연결 설정을 생성하고 수정할 수 있습니다', 'apiconnection');
INSERT INTO permission (id, code, description, category) VALUES (33, 'apiconnection:delete', 'API 연결 설정을 삭제할 수 있습니다', 'apiconnection');
INSERT INTO permission (id, code, description, category) VALUES (34, 'analytics:read', '분석 자산 조회 (쿼리, 차트, 대시보드)', 'analytics');
INSERT INTO permission (id, code, description, category) VALUES (35, 'analytics:write', '분석 자산 생성/수정/삭제', 'analytics');
INSERT INTO permission (id, code, description, category) VALUES (37, 'pipeline:ai_execute', 'AI 분류 스텝 실행 권한', 'pipeline');
INSERT INTO permission (id, code, description, category) VALUES (36, 'pipeline:python_execute', '파이프라인 Python 스크립트 실행 (nsjail 샌드박스 격리)', 'pipeline');
INSERT INTO permission (id, code, description, category) VALUES (38, 'proactive:read', 'Proactive AI 리포트 조회', 'proactive');
INSERT INTO permission (id, code, description, category) VALUES (39, 'proactive:write', 'Proactive AI 작업 관리', 'proactive');
INSERT INTO permission (id, code, description, category) VALUES (44, 'ontology:write', '지식 모델(온톨로지) 편집', 'ontology');
INSERT INTO permission (id, code, description, category) VALUES (45, 'platform:tenant:create', '테넌트 생성', 'platform');
INSERT INTO permission (id, code, description, category) VALUES (46, 'platform:tenant:read', '테넌트 조회', 'platform');
INSERT INTO permission (id, code, description, category) VALUES (47, 'platform:tenant:suspend', '테넌트 정지/활성화', 'platform');
INSERT INTO permission (id, code, description, category) VALUES (48, 'platform:member:read', '테넌트 멤버 조회', 'platform');
INSERT INTO permission (id, code, description, category) VALUES (51, 'security:settings', '보안 등급·정책 관리', 'security');
INSERT INTO permission (id, code, description, category) VALUES (52, 'dataset:classify', '데이터셋 보안 등급 변경', 'security');
INSERT INTO permission (id, code, description, category) VALUES (53, 'dataset:grant', '데이터셋 허용 목록 관리', 'security');
INSERT INTO permission (id, code, description, category) VALUES (54, 'data:export_restricted', '''권한 필요'' 등급 데이터 내보내기', 'security');
INSERT INTO permission (id, code, description, category) VALUES (55, 'proactive:admin', '스마트 작업 관리(관리자) — 워크스페이스의 모든 스마트 작업을 보고 끄거나 삭제', 'proactive');
INSERT INTO permission (id, code, description, category) VALUES (56, 'analytics:admin', '분석 자산 관리(관리자) — 워크스페이스의 모든 대시보드·차트·저장 쿼리를 보고 공유 해제하거나 삭제', 'analytics');
INSERT INTO permission (id, code, description, category) VALUES (57, 'notification:admin', '알림 관리(관리자) — 워크스페이스의 알림 발송 대기열을 보고 실패한 알림을 다시 보내며, Slack 워크스페이스를 설치', 'notification');

-- role (2행)
INSERT INTO role (id, name, description, is_system, tenant_id, max_security_level_id) VALUES (1, 'ADMIN', '시스템 관리자', 1, 1, 4);
INSERT INTO role (id, name, description, is_system, tenant_id, max_security_level_id) VALUES (2, 'USER', '일반 사용자', 1, 1, 2);

-- role_permission (52행)
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 1, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 3, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 4, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 6, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 7, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 8, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 9, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 10, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 11, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 12, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 13, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 14, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 15, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 16, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 17, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 18, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 19, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 20, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 21, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 24, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 25, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 26, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 27, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 28, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 29, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 30, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 31, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 32, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 33, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 34, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 35, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 37, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 36, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 38, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 39, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 44, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (2, 3, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (2, 11, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (2, 14, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (2, 24, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (2, 25, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (2, 31, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (2, 34, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (2, 35, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (2, 38, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 51, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 52, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 53, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 54, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 55, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 56, 1);
INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (1, 57, 1);

-- platform_role (1행)
INSERT INTO platform_role (id, name, description, is_system) VALUES (1, 'SUPER_ADMIN', '플랫폼 전체 운영자', 1);

-- platform_role_permission (4행)
INSERT INTO platform_role_permission (platform_role_id, permission_id) VALUES (1, 45);
INSERT INTO platform_role_permission (platform_role_id, permission_id) VALUES (1, 46);
INSERT INTO platform_role_permission (platform_role_id, permission_id) VALUES (1, 47);
INSERT INTO platform_role_permission (platform_role_id, permission_id) VALUES (1, 48);

-- dataset_category (3행)
INSERT INTO dataset_category (id, name, description, tenant_id) VALUES (1, '행정', '행정 업무 관련 데이터', 1);
INSERT INTO dataset_category (id, name, description, tenant_id) VALUES (2, '운영', '현장 운영 관련 데이터', 1);
INSERT INTO dataset_category (id, name, description, tenant_id) VALUES (3, '통계', '통계 및 분석 데이터', 1);

-- report_template (3행)
INSERT INTO report_template (id, name, description, sections, user_id, style, tenant_id) VALUES (1, '일간 요약 리포트', '매일 실행 결과와 데이터 현황을 요약합니다.', '[{"key": "summary", "type": "text", "label": "요약", "required": true, "instruction": "오늘 시스템의 전반적인 상태를 3-5문장으로 요약하세요. 가장 주목할 변화를 먼저 언급하세요."}, {"key": "stats", "type": "cards", "label": "통계", "required": false, "instruction": "파이프라인 총 실행 건수, 성공률, 데이터셋 수, 활성 사용자 수를 카드로 표시하세요."}, {"key": "details", "type": "list", "label": "상세 내역", "required": false, "instruction": "실패한 파이프라인, 새로 생성된 데이터셋, 주요 변경사항을 나열하세요."}, {"key": "attention", "type": "list", "label": "주의 항목", "required": false, "instruction": "즉시 조치가 필요한 항목을 심각도 순으로 나열하세요."}, {"key": "recommendation", "type": "recommendation", "label": "권장 사항", "required": false, "instruction": "데이터를 기반으로 구체적 개선 조치를 제안하세요."}]', NULL, '간결한 경영진 보고 스타일. 핵심 변화를 먼저 서술하고, 수치는 맥락과 함께 제시. 이전 실행과 비교하여 변화 추이를 언급.', 1);
INSERT INTO report_template (id, name, description, sections, user_id, style, tenant_id) VALUES (2, '실패 분석 리포트', '실패한 작업에 대한 원인 분석과 영향도를 보고합니다.', '[{"key": "overview", "type": "text", "label": "개요", "required": true, "instruction": "실패 현황을 전체 대비 비율과 함께 요약하세요."}, {"key": "failures", "type": "list", "label": "실패 목록", "required": false, "instruction": "실패한 파이프라인/작업을 시간순으로 나열하세요. 각 항목에 실패 원인을 한 줄로 추가하세요."}, {"key": "analysis", "type": "text", "label": "원인 분석", "required": false, "instruction": "공통 패턴이나 근본 원인을 분석하세요. 가능하면 연관된 실패를 그룹핑하세요."}, {"key": "impact", "type": "text", "label": "영향도", "required": false, "instruction": "실패로 인한 비즈니스 영향과 데이터 파이프라인 연쇄 영향을 평가하세요."}, {"key": "resolution", "type": "recommendation", "label": "해결 방안", "required": false, "instruction": "각 실패 유형별로 구체적 해결 단계와 재발 방지 조치를 제안하세요."}]', NULL, '기술 분석 스타일. 실패 현상 → 근본 원인 → 영향도 → 해결 방안 순서로 논리적 서술. 재발 방지 관점의 권고사항 포함.', 1);
INSERT INTO report_template (id, name, description, sections, user_id, style, tenant_id) VALUES (3, '주간 트렌드 리포트', '주간 데이터 트렌드와 변화 추이를 분석합니다.', '[{"key": "summary", "type": "text", "label": "주간 요약", "required": true, "instruction": "이번 주 가장 주목할 변화 3가지를 요약하세요. 지난주 대비 개선/악화를 명확히 구분하세요."}, {"key": "comparison", "type": "cards", "label": "전주 비교", "required": false, "instruction": "핵심 지표의 전주 대비 변화를 카드로 표시하세요. 변화율(%)을 description에 포함하세요."}, {"key": "trends", "type": "list", "label": "트렌드", "required": false, "instruction": "최근 4주간의 추세를 분석하세요. 단기 변동과 중기 트렌드를 구분하세요."}, {"key": "highlights", "type": "alert", "label": "주요 이슈", "required": false, "instruction": "이번 주 발생한 주요 이슈를 심각도 순으로 나열하세요."}, {"key": "outlook", "type": "text", "label": "전망", "required": false, "instruction": "다음 주 예상되는 변화와 주의할 점을 서술하세요."}]', NULL, '트렌드 분석 스타일. 이번 주와 지난주를 비교하여 변화율 중심 서술. 단기(1주) 변화와 중기(4주) 추세를 구분. 수치에는 반드시 변화율(%) 병기.', 1);

-- ontology (1행)
INSERT INTO ontology (id, domain, schema_version, status, tenant_id) VALUES (1, '화재조사 보고서', 1, 'active', 1);

-- ontology_entity_type (6행)
INSERT INTO ontology_entity_type (id, ontology_id, type, description, naming, resolution, sort_order, tenant_id) VALUES (1, 1, 'Incident', '사건/이벤트 (예: 발생한 화재)', '문서마다 고유해야 한다. 핵심 식별 속성(장소·일자 등)을 포함해 구성하고, 일반명이나 문서 번호를 이름으로 쓰지 마라. 한 문서에서 정확히 1개만 추출한다.', 'exact', 0, 1);
INSERT INTO ontology_entity_type (id, ontology_id, type, description, naming, resolution, sort_order, tenant_id) VALUES (2, 1, 'Building', '물리적 장소/건물', '본문에 등장한 표기를 그대로 사용한다.', 'embedding', 1, 1);
INSERT INTO ontology_entity_type (id, ontology_id, type, description, naming, resolution, sort_order, tenant_id) VALUES (3, 1, 'Cause', '발화·발생 원인', '본문에 등장한 표기를 그대로 사용한다.', 'embedding', 2, 1);
INSERT INTO ontology_entity_type (id, ontology_id, type, description, naming, resolution, sort_order, tenant_id) VALUES (4, 1, 'Damage', '피해 내역', '본문에 등장한 표기를 그대로 사용한다.', 'exact', 3, 1);
INSERT INTO ontology_entity_type (id, ontology_id, type, description, naming, resolution, sort_order, tenant_id) VALUES (5, 1, 'Equipment', '소방 설비/장비', '본문에 등장한 표기를 그대로 사용한다.', 'embedding', 4, 1);
INSERT INTO ontology_entity_type (id, ontology_id, type, description, naming, resolution, sort_order, tenant_id) VALUES (6, 1, 'Regulation', '관련 법규/기준', '본문에 등장한 표기를 그대로 사용한다.', 'embedding', 5, 1);

-- ontology_entity_property (1행)
INSERT INTO ontology_entity_property (id, entity_type_id, name, description, data_type, unit, sort_order, tenant_id) VALUES (1, 1, '피해액', '화재로 인한 재산 피해 총액을 원 단위 정수로 추출. ''억''=1e8, ''만''=1e4 로 환산하고 콤마·통화기호 제거. 범위·근사값은 대표값 1개만.', 'number', '원', 0, 1);

-- ontology_relation (6행)
INSERT INTO ontology_relation (id, ontology_id, relation, description, sort_order, subject_type_id, object_type_id, tenant_id) VALUES (1, 1, 'OCCURRED_AT', '사건이 발생한 장소', 0, 1, 2, 1);
INSERT INTO ontology_relation (id, ontology_id, relation, description, sort_order, subject_type_id, object_type_id, tenant_id) VALUES (2, 1, 'CAUSED_BY', '사건의 발화·발생 원인', 1, 1, 3, 1);
INSERT INTO ontology_relation (id, ontology_id, relation, description, sort_order, subject_type_id, object_type_id, tenant_id) VALUES (3, 1, 'RESULTED_IN', '사건이 초래한 피해', 2, 1, 4, 1);
INSERT INTO ontology_relation (id, ontology_id, relation, description, sort_order, subject_type_id, object_type_id, tenant_id) VALUES (4, 1, 'HAS_EQUIPMENT', '건물이 보유한 설비', 3, 2, 5, 1);
INSERT INTO ontology_relation (id, ontology_id, relation, description, sort_order, subject_type_id, object_type_id, tenant_id) VALUES (5, 1, 'VIOLATED', '사건에서 위반된 규정', 4, 1, 6, 1);
INSERT INTO ontology_relation (id, ontology_id, relation, description, sort_order, subject_type_id, object_type_id, tenant_id) VALUES (6, 1, 'GOVERNED_BY', '설비를 규율하는 규정', 5, 5, 6, 1);

COMMIT;
