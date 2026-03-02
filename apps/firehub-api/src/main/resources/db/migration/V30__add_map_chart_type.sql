-- V30: MAP 차트 타입 추가
ALTER TABLE chart DROP CONSTRAINT IF EXISTS chart_chart_type_check;
ALTER TABLE chart ADD CONSTRAINT chart_chart_type_check
    CHECK (chart_type IN ('BAR', 'LINE', 'PIE', 'AREA', 'SCATTER', 'DONUT', 'TABLE', 'MAP'));
