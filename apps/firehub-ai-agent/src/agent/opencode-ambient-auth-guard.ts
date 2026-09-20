import { stat } from 'fs/promises';
import { homedir } from 'os';
import { join } from 'path';
import { AdminActionableError } from './admin-actionable-error.js';

/**
 * opencode 의 **데이터** 디렉터리에 ambient provider 인증 파일이 남아 있는지 확인한다 (이슈 #697).
 *
 * <p><b>무엇이 문제인가.</b> #693 은 opencode 자식의 `XDG_CONFIG_HOME` 을 프로세스 전용 임시
 * 디렉터리로 돌려 배포 전역 `opencode.json` 상속을 끊었다. 그런데 opencode 는 provider
 * <b>인증</b>을 `$XDG_DATA_HOME/opencode/auth.json`(기본 `~/.local/share/opencode`)에도 둔다.
 * 데이터 디렉터리는 `--session` 재개가 의존하므로 <b>의도적으로 건드리지 않는다</b> — 즉 설정
 * 디렉터리만 끊는 가드로는 ambient 자격증명 경로가 완전히 닫히지 않았다.
 *
 * <h2>실측 (2026-09-21, opencode v1.18.31)</h2>
 *
 * <p>깨끗한 `XDG_DATA_HOME` 으로 `OPENCODE_CONFIG_CONTENT` 만 주고 실행한 결과:
 *
 * <ul>
 *   <li>`auth.json` 은 <b>생기지 않는다</b>. 데이터 디렉터리에 만들어진 것은 세션 DB
 *       (`opencode.db*`), 로그, 빈 `repos/` 뿐이다. 그 파일은 `opencode auth login` 의
 *       산물이지 정상 실행의 산물이 아니다.
 *   <li>테넌트 apiKey 가 데이터 디렉터리 <b>어디에도 남지 않는다</b>(전체 재귀 grep 0건).
 *       설정을 env 로만 주입하는 현재 설계가 디스크 잔류를 실제로 막고 있다는 확인이다.
 * </ul>
 *
 * <p>따라서 위험은 "우리가 만든 파일"이 아니라 <b>미리 심어진 파일</b>뿐이다 — 차트가 그
 * 디렉터리를 마운트하거나, 이미지에 포함되거나, 누군가 컨테이너에서 `auth login` 을 한 경우.
 * 정상 배포라면 이 가드는 평생 한 번도 발동하지 않는다.
 *
 * <p><b>지금까지 실 위험이 없던 근거는 코드가 아니라 차트였다.</b> 배포 차트가
 * `.local/share/opencode` 를 마운트하지 않는다는 조건 하나가 전부였고, 그 마운트가 추가되는
 * 순간 조용히 무너진다. 강제 수단이 없는 문서 한 줄(`deploy.md`)에 기대고 있었다.
 *
 * <p><b>왜 기동 실패가 아니라 요청 단위 검사인가.</b> ai-agent 는 opencode 테넌트만 쓰는 것이
 * 아니다. 기동 시 프로세스를 죽이면 sdk/cli/cli-api 테넌트의 채팅까지 opencode 전용 위험 때문에
 * 전부 멈춘다 — 위험의 범위보다 대응의 범위가 훨씬 넓어진다. 그래서 opencode 를 실제로 스폰하는
 * 순간에만 검사해 그 요청만 실패시킨다. 파일이 <b>어떤 경로로 생겼든</b> 똑같이 걸린다는 점에서
 * "마운트가 있는지"만 보는 차트 쪽 검사보다 넓다 — 차트 쪽 가드는 그 위의 두 번째 방어선이다.
 */

/** opencode 가 ambient provider 인증을 두는 파일들. 둘 중 하나만 있어도 차단한다. */
const AMBIENT_AUTH_FILES = ['auth.json', 'mcp-auth.json'] as const;

/**
 * 로컬 개발 탈출구. **운영에는 절대 설정하지 않는다**(`deploy.md` 에 그렇게 적혀 있다).
 *
 * <p>개발자 노트북은 보통 자기 계정으로 `opencode auth login` 을 한 적이 있어 이 파일이 이미
 * 있다. 그 경우 잘못 과금되는 대상은 플랫폼이 아니라 개발자 자신이고, 위험의 성격이 운영과
 * 다르다 — 그래서 로컬에서만 끌 수 있게 한다. 끄면 매 요청 경고를 남긴다(조용히 꺼져 있는
 * 가드가 가장 나쁘다).
 */
export const ALLOW_AMBIENT_AUTH_VAR = 'OPENCODE_ALLOW_AMBIENT_AUTH';

/**
 * 자식에게 줄 env 기준으로 opencode 데이터 디렉터리를 계산한다. 자식이 실제로 읽을 경로와
 * 같아야 의미가 있으므로 `process.env` 가 아니라 **자식 env** 를 받는다.
 */
export function opencodeDataDir(childEnv: NodeJS.ProcessEnv): string {
  const base = childEnv.XDG_DATA_HOME || join(childEnv.HOME || homedir(), '.local', 'share');
  return join(base, 'opencode');
}

/** 데이터 디렉터리에 있는 ambient 인증 파일들의 경로. 없으면 빈 배열. */
export async function findAmbientAuthFiles(childEnv: NodeJS.ProcessEnv): Promise<string[]> {
  const dir = opencodeDataDir(childEnv);
  const found: string[] = [];
  for (const file of AMBIENT_AUTH_FILES) {
    const path = join(dir, file);
    try {
      await stat(path);
      found.push(path);
    } catch {
      // 없는 것이 정상이다 — 위 실측 참고.
    }
  }
  return found;
}

/**
 * ambient 인증 파일이 있으면 던진다. 없으면 조용히 통과한다.
 *
 * @param childEnv opencode 자식에게 줄 환경변수(경로 계산의 기준).
 * @param processEnv 탈출구를 읽을 ai-agent 자신의 환경변수. 자식 env 가 아니다 — 탈출구는
 *     opencode 에 전달되지 않는 운영/개발 스위치이고, allowlist 도 그것을 통과시키지 않는다.
 */
export async function assertNoAmbientOpencodeAuth(
  childEnv: NodeJS.ProcessEnv,
  processEnv: NodeJS.ProcessEnv = process.env,
): Promise<void> {
  const found = await findAmbientAuthFiles(childEnv);
  if (found.length === 0) return;

  if (processEnv[ALLOW_AMBIENT_AUTH_VAR] === '1') {
    console.warn(
      `[opencode] ambient 인증 파일이 있지만 ${ALLOW_AMBIENT_AUTH_VAR}=1 이라 통과시킨다: ${found.join(', ')}.` +
        ' 이 설정은 로컬 개발 전용이다 — 운영에 있으면 테넌트 설정 대신 배포 전역 자격증명이 쓰일 수 있다.',
    );
    return;
  }

  // 사용자 메시지에는 경로를 담지 않는다 — 채팅 사용자가 반드시 플랫폼 관리자는 아니다.
  // 실제 파일 경로는 detail 로 넘겨 로그에만 남긴다.
  throw new AdminActionableError(
    'opencode 실행 환경에 배포 측 provider 인증 파일이 남아 있어 실행을 중단했습니다.' +
      ' 그대로 두면 테넌트가 설정한 공급자 대신 배포 전역 자격증명으로 과금될 수 있습니다.' +
      ' 플랫폼 관리자에게 문의하세요.',
    `ambient auth files: ${found.join(', ')} —` +
      ' 해당 파일을 제거하고 배포에서 이 디렉터리를 마운트하지 마세요' +
      ` (.claude/docs/deploy.md 의 OpenCode 운영 요건 참고). 로컬 개발이라면 ${ALLOW_AMBIENT_AUTH_VAR}=1 로 끌 수 있습니다.`,
  );
}
