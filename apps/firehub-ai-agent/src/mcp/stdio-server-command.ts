import { existsSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

/** firehub MCP stdio 서버 실행 command/args 해석 (prod: node dist, dev: tsx src). */
export function getStdioServerCommand(): { command: string; args: string[] } {
  const __filename = fileURLToPath(import.meta.url);
  const __dirname = dirname(__filename);
  const serverJs = join(__dirname, 'stdio-server.js');
  if (existsSync(serverJs)) return { command: 'node', args: [serverJs] };
  const serverTs = join(__dirname, 'stdio-server.ts');
  const tsxBin = join(__dirname, '..', '..', 'node_modules', '.bin', 'tsx');
  return { command: tsxBin, args: [serverTs] };
}
