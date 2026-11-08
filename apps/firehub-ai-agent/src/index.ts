import dotenv from 'dotenv';
import { createApp } from './app.js';
import { DEFAULT_PORT } from './constants.js';

dotenv.config({ path: '.env.local' });

// 앱 조립(미들웨어·라우터·에러 핸들러)은 app.ts 에서 한다 — 테스트가 같은 구성을 그대로 검증하기 위함(WD-550)
const app = createApp();
const PORT = process.env.PORT || DEFAULT_PORT;

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
