-- Slack inbound 대화 컨텍스트 컬럼 추가.
-- channel_source: 세션 출처 구분 ('WEB' | 'SLACK' | 'KAKAO'). 기존 행은 'WEB'으로 채워짐.
-- slack_team_id / slack_channel_id / slack_thread_ts: Slack 스레드 단위 세션 식별자.
ALTER TABLE ai_session
    ADD COLUMN IF NOT EXISTS channel_source  VARCHAR(16) NOT NULL DEFAULT 'WEB',
    ADD COLUMN IF NOT EXISTS slack_team_id   VARCHAR(32),
    ADD COLUMN IF NOT EXISTS slack_channel_id VARCHAR(32),
    ADD COLUMN IF NOT EXISTS slack_thread_ts  VARCHAR(32);

-- channel_source 허용 값 제약. 이후 채널 추가 시 마이그레이션으로 확장.
ALTER TABLE ai_session
    ADD CONSTRAINT chk_ai_session_channel_source
        CHECK (channel_source IN ('WEB', 'SLACK', 'KAKAO'));

-- SLACK 세션은 (team_id, channel_id, thread_ts) 3-tuple이 UNIQUE해야 한다.
-- 같은 스레드의 후속 메시지가 동일 ai_session을 재사용하도록 강제.
-- Partial UNIQUE INDEX: channel_source='SLACK' 행에만 적용.
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_session_slack_thread
    ON ai_session(slack_team_id, slack_channel_id, slack_thread_ts)
    WHERE channel_source = 'SLACK';

-- Slack 워크스페이스·채널 기준 빠른 조회를 위한 일반 인덱스.
CREATE INDEX IF NOT EXISTS idx_ai_session_slack_lookup
    ON ai_session(slack_team_id, slack_channel_id);
