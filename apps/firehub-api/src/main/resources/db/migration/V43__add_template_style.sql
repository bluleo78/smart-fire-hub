-- 리포트 템플릿에 style 컬럼 추가
ALTER TABLE report_template ADD COLUMN IF NOT EXISTS style TEXT;

-- 빌트인 템플릿에 기본 스타일 설정
UPDATE report_template SET style = '간결한 경영진 보고 스타일. 핵심 변화를 먼저 서술하고, 수치는 맥락과 함께 제시. 이전 실행과 비교하여 변화 추이를 언급.'
WHERE name = '일간 요약 리포트' AND user_id IS NULL;

UPDATE report_template SET style = '기술 분석 스타일. 실패 현상 → 근본 원인 → 영향도 → 해결 방안 순서로 논리적 서술. 재발 방지 관점의 권고사항 포함.'
WHERE name = '실패 분석 리포트' AND user_id IS NULL;

UPDATE report_template SET style = '트렌드 분석 스타일. 이번 주와 지난주를 비교하여 변화율 중심 서술. 단기(1주) 변화와 중기(4주) 추세를 구분. 수치에는 반드시 변화율(%) 병기.'
WHERE name = '주간 트렌드 리포트' AND user_id IS NULL;
