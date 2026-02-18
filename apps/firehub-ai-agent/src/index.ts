import express from 'express';
import dotenv from 'dotenv';
import chatRouter from './routes/chat.js';

dotenv.config({ path: '.env.local' });

const app = express();
const PORT = process.env.PORT || 3001;

app.use(express.json());

app.use('/agent', chatRouter);

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
