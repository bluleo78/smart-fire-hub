import express from 'express';
import dotenv from 'dotenv';
import chatRouter from './routes/chat.js';
import classifyRouter from './routes/classify.js';
import proactiveRouter from './routes/proactive.js';
import chartRenderRouter from './routes/chart-render.js';
import { DEFAULT_PORT } from './constants.js';

dotenv.config({ path: '.env.local' });

const app = express();
const PORT = process.env.PORT || DEFAULT_PORT;

app.use(express.json());

app.use('/agent', chatRouter);
app.use('/agent', classifyRouter);
app.use('/agent', proactiveRouter);
app.use('/agent', chartRenderRouter);

app.use((err: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
  console.error('Unhandled error:', err);
  res.status(500).json({
    error: 'Internal server error',
  });
});

const server = app.listen(PORT, () => {
  console.log(`FireHub AI Agent service running on port ${PORT}`);
  console.log(`Health check: http://localhost:${PORT}/agent/health`);
  console.log(`Chat endpoint: POST http://localhost:${PORT}/agent/chat`);
});

function shutdown(signal: string) {
  console.log(`${signal} received, shutting down gracefully...`);
  server.close(() => {
    process.exit(0);
  });
  setTimeout(() => {
    process.exit(1);
  }, 5000);
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

// Claude Agent SDK 내부에서 abort 시 unhandled rejection이 발생하여
// 프로세스 전체가 크래시되는 것을 방지
process.on('unhandledRejection', (reason: unknown) => {
  const message = reason instanceof Error ? reason.message : String(reason);
  if (message.includes('aborted') || message.includes('Operation aborted')) {
    console.warn('[Process] Suppressed SDK abort rejection:', message);
  } else {
    console.error('[Process] Unhandled rejection:', reason);
  }
});
