import axios from 'axios';

/**
 * firehub-api로 Slack inbound 이벤트를 포워딩
 * 내부 서비스 인증 토큰을 Authorization 헤더에 포함
 */
export async function forwardSlackInbound(teamId: string, event: unknown): Promise<void> {
  const baseUrl = process.env.FIREHUB_API_BASE_URL ?? 'http://api:8080';
  const token = process.env.INTERNAL_TOKEN;
  await axios.post(
    `${baseUrl}/api/v1/channels/slack/inbound`,
    { teamId, event },
    { headers: { Authorization: `Internal ${token}` } },
  );
}
