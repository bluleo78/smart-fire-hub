import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import { sendRouter } from './routes/send.js';
import { slackEventsRouter } from './routes/slack-events.js';

const app = express();
const PORT = process.env.PORT ?? '3002';

app.use(cors());

// /slack/events는 raw body가 필요 (서명 검증)
app.use('/slack', express.raw({ type: '*/*' }), slackEventsRouter);

// 나머지 경로는 JSON
app.use(express.json());
app.use('/send', sendRouter);

app.get('/health', (_req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

app.listen(Number(PORT), () => {
  console.log(`firehub-channel listening on port ${PORT}`);
});
