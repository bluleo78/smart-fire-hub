-- 빌트인 템플릿에 instruction 필드 추가

-- 일간 요약 리포트
UPDATE report_template
SET sections = '[
    {"key": "summary", "label": "요약", "required": true, "type": "text", "instruction": "오늘 시스템의 전반적인 상태를 3-5문장으로 요약하세요. 가장 주목할 변화를 먼저 언급하세요."},
    {"key": "stats", "label": "통계", "required": false, "type": "cards", "instruction": "파이프라인 총 실행 건수, 성공률, 데이터셋 수, 활성 사용자 수를 카드로 표시하세요."},
    {"key": "details", "label": "상세 내역", "required": false, "type": "list", "instruction": "실패한 파이프라인, 새로 생성된 데이터셋, 주요 변경사항을 나열하세요."},
    {"key": "attention", "label": "주의 항목", "required": false, "type": "list", "instruction": "즉시 조치가 필요한 항목을 심각도 순으로 나열하세요."},
    {"key": "recommendation", "label": "권장 사항", "required": false, "type": "recommendation", "instruction": "데이터를 기반으로 구체적 개선 조치를 제안하세요."}
]'::jsonb
WHERE name = '일간 요약 리포트' AND user_id IS NULL;

-- 실패 분석 리포트
UPDATE report_template
SET sections = '[
    {"key": "overview", "label": "개요", "required": true, "type": "text", "instruction": "실패 현황을 전체 대비 비율과 함께 요약하세요."},
    {"key": "failures", "label": "실패 목록", "required": false, "type": "list", "instruction": "실패한 파이프라인/작업을 시간순으로 나열하세요. 각 항목에 실패 원인을 한 줄로 추가하세요."},
    {"key": "analysis", "label": "원인 분석", "required": false, "type": "text", "instruction": "공통 패턴이나 근본 원인을 분석하세요. 가능하면 연관된 실패를 그룹핑하세요."},
    {"key": "impact", "label": "영향도", "required": false, "type": "text", "instruction": "실패로 인한 비즈니스 영향과 데이터 파이프라인 연쇄 영향을 평가하세요."},
    {"key": "resolution", "label": "해결 방안", "required": false, "type": "recommendation", "instruction": "각 실패 유형별로 구체적 해결 단계와 재발 방지 조치를 제안하세요."}
]'::jsonb
WHERE name = '실패 분석 리포트' AND user_id IS NULL;

-- 주간 트렌드 리포트
UPDATE report_template
SET sections = '[
    {"key": "summary", "label": "주간 요약", "required": true, "type": "text", "instruction": "이번 주 가장 주목할 변화 3가지를 요약하세요. 지난주 대비 개선/악화를 명확히 구분하세요."},
    {"key": "comparison", "label": "전주 비교", "required": false, "type": "cards", "instruction": "핵심 지표의 전주 대비 변화를 카드로 표시하세요. 변화율(%)을 description에 포함하세요."},
    {"key": "trends", "label": "트렌드", "required": false, "type": "list", "instruction": "최근 4주간의 추세를 분석하세요. 단기 변동과 중기 트렌드를 구분하세요."},
    {"key": "highlights", "label": "주요 이슈", "required": false, "type": "alert", "instruction": "이번 주 발생한 주요 이슈를 심각도 순으로 나열하세요."},
    {"key": "outlook", "label": "전망", "required": false, "type": "text", "instruction": "다음 주 예상되는 변화와 주의할 점을 서술하세요."}
]'::jsonb
WHERE name = '주간 트렌드 리포트' AND user_id IS NULL;
