import axios from 'axios';

const SLACK_API = 'https://slack.com/api';

interface SendMessageParams {
  botToken: string;
  channel: string;
  text: string;
  threadTs?: string;
  blocks?: unknown[];
}

interface ReactionParams {
  botToken: string;
  channel: string;
  timestamp: string;
  name: string;
}

interface EphemeralParams {
  botToken: string;
  channel: string;
  user: string;
  text: string;
}

export async function sendSlackMessage(params: SendMessageParams): Promise<{ ok: boolean; ts?: string }> {
  const { data } = await axios.post(
    `${SLACK_API}/chat.postMessage`,
    {
      channel: params.channel,
      text: params.text,
      ...(params.threadTs ? { thread_ts: params.threadTs } : {}),
      ...(params.blocks ? { blocks: params.blocks } : {}),
    },
    { headers: { Authorization: `Bearer ${params.botToken}`, 'Content-Type': 'application/json' } },
  );
  if (!data.ok) throw new Error(data.error ?? 'slack_error');
  return data as { ok: boolean; ts?: string };
}

export async function addSlackReaction(params: ReactionParams): Promise<void> {
  const { data } = await axios.post(
    `${SLACK_API}/reactions.add`,
    { channel: params.channel, timestamp: params.timestamp, name: params.name },
    { headers: { Authorization: `Bearer ${params.botToken}`, 'Content-Type': 'application/json' } },
  );
  if (!data.ok && data.error !== 'already_reacted') throw new Error(data.error ?? 'reaction_error');
}

export async function postSlackEphemeral(params: EphemeralParams): Promise<void> {
  const { data } = await axios.post(
    `${SLACK_API}/chat.postEphemeral`,
    { channel: params.channel, user: params.user, text: params.text },
    { headers: { Authorization: `Bearer ${params.botToken}`, 'Content-Type': 'application/json' } },
  );
  if (!data.ok) throw new Error(data.error ?? 'ephemeral_error');
}
