import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import net from 'node:net';
import { tmpdir } from 'node:os';
import path from 'node:path';
import tls from 'node:tls';
import nodemailer from 'nodemailer';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { sendEmail } from './email.js';

/**
 * 진짜 nodemailer 로 SMTP 대화를 끝까지 해 보는 시험(WD-790).
 * email.test.ts 는 nodemailer 를 통째로 흉내 내서 nodemailer 메이저를 올려도(6 → 10) 실제 createTransport 옵션·
 * AUTH·DATA 흐름이 깨진 것을 잡지 못한다. 그래서 같은 프로세스에 최소 SMTP 서버를 띄워 실제로 한 통을 보내 본다.
 * 운영 SMTP 는 대개 STARTTLS(587) 나 암시적 TLS(465) 이므로 평문뿐 아니라 두 TLS 경로도 진짜 TLS 핸드셰이크로 확인한다.
 */

/** 최소 SMTP 서버가 받은 내용 — AUTH 자격증명, 봉투(MAIL FROM/RCPT TO), 본문(DATA), AUTH 가 TLS 위에서 왔는지 */
interface Received {
  authPlain?: string;
  /** AUTH 명령이 TLS 로 암호화된 연결에서 왔는지 — 자격증명이 평문으로 나가지 않았음을 확인한다 */
  authOverTls?: boolean;
  mailFrom?: string;
  rcptTo: string[];
  data: string;
}

/** 시험용 TLS 키·인증서(PEM). 운영 코드에는 없는 자체 서명 인증서다 */
interface TlsMaterial {
  key: string;
  cert: string;
}

/**
 * 가짜 서버의 TLS 모드.
 * - plain: TLS 없음(STARTTLS 도 광고하지 않음)
 * - starttls: 평문으로 받은 뒤 STARTTLS 를 광고하고, 명령이 오면 같은 소켓을 TLS 로 승격한다
 * - implicit: 접속 즉시 TLS(관례상 465 포트)
 */
type FakeSmtpMode = { kind: 'plain' } | { kind: 'starttls'; tls: TlsMaterial } | { kind: 'implicit'; tls: TlsMaterial };

/**
 * 한 연결의 SMTP 대화를 처리한다 — EHLO·STARTTLS·AUTH PLAIN·MAIL·RCPT·DATA·QUIT 만 안다.
 * STARTTLS 를 받으면 원래 소켓의 data 처리기를 떼고, 그 소켓을 TLS 서버 소켓으로 감싼 뒤 같은 처리기로 대화를 이어 간다.
 */
function handleSmtpConnection(
  socket: net.Socket,
  received: Received,
  mode: FakeSmtpMode,
  secured: boolean,
  greet: boolean,
): void {
  let inData = false;
  let buffer = '';
  // 아직 평문인 STARTTLS 서버인지 — 이 연결(처리기) 동안 바뀌지 않으므로 한 번만 계산해 광고·승격 판정에 함께 쓴다
  const canStartTls = mode.kind === 'starttls' && !secured;
  // 클라이언트가 비정상 종료(ECONNRESET 등)해도 처리되지 않은 소켓 오류로 시험이 흔들리지 않게 삼킨다
  socket.on('error', () => {});
  if (greet) socket.write('220 fake.smtp ESMTP\r\n');
  const onData = (chunk: Buffer): void => {
    buffer += chunk.toString('utf8');
    // 줄 단위로 처리한다 — DATA 중에는 "." 한 줄이 나올 때까지 본문으로 모은다.
    let idx: number;
    while ((idx = buffer.indexOf('\r\n')) >= 0) {
      const line = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      if (inData) {
        if (line === '.') {
          inData = false;
          socket.write('250 2.0.0 queued\r\n');
        } else {
          received.data += line + '\n';
        }
        continue;
      }
      const upper = line.toUpperCase();
      if (upper.startsWith('EHLO') || upper.startsWith('HELO')) {
        // 아직 평문인 STARTTLS 서버만 STARTTLS 를 광고한다(승격 뒤에는 다시 광고하지 않는 것이 규약)
        socket.write(canStartTls ? '250-fake.smtp\r\n250-STARTTLS\r\n250 AUTH PLAIN\r\n' : '250-fake.smtp\r\n250 AUTH PLAIN\r\n');
      } else if (upper === 'STARTTLS' && canStartTls) {
        // 승격: 평문 처리기를 떼어 TLS 핸드셰이크 바이트가 SMTP 명령으로 읽히지 않게 한 뒤 소켓을 TLS 로 감싼다
        socket.off('data', onData);
        socket.write('220 2.0.0 ready to start TLS\r\n');
        const secureSocket = new tls.TLSSocket(socket, { isServer: true, key: mode.tls.key, cert: mode.tls.cert });
        // 승격 뒤에는 인사(220) 없이 클라이언트의 새 EHLO 를 기다린다
        handleSmtpConnection(secureSocket, received, mode, true, false);
        return;
      } else if (upper.startsWith('AUTH PLAIN')) {
        received.authPlain = line.slice('AUTH PLAIN '.length);
        received.authOverTls = secured;
        socket.write('235 2.7.0 ok\r\n');
      } else if (upper.startsWith('MAIL FROM:')) {
        received.mailFrom = line.slice('MAIL FROM:'.length);
        socket.write('250 ok\r\n');
      } else if (upper.startsWith('RCPT TO:')) {
        received.rcptTo.push(line.slice('RCPT TO:'.length));
        socket.write('250 ok\r\n');
      } else if (upper === 'DATA') {
        inData = true;
        socket.write('354 go ahead\r\n');
      } else if (upper === 'QUIT') {
        socket.end('221 bye\r\n');
      } else {
        socket.write('502 not implemented\r\n');
      }
    }
  };
  socket.on('data', onData);
}

/** 지정한 TLS 모드의 최소 SMTP 서버를 임의 포트에 띄운다 */
function startFakeSmtp(
  mode: FakeSmtpMode = { kind: 'plain' },
): Promise<{ port: number; received: Received; close: () => Promise<void> }> {
  const received: Received = { rcptTo: [], data: '' };
  const server =
    mode.kind === 'implicit'
      ? tls.createServer({ key: mode.tls.key, cert: mode.tls.cert }, (socket) =>
          handleSmtpConnection(socket, received, mode, true, true),
        )
      : net.createServer((socket) => handleSmtpConnection(socket, received, mode, false, true));
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address() as net.AddressInfo;
      resolve({
        port,
        received,
        close: () => new Promise<void>((r) => server.close(() => r())),
      });
    });
  });
}

/**
 * 127.0.0.1 용 자체 서명 인증서를 openssl 로 즉석에서 만든다.
 * 개인 키를 저장소에 넣지 않으려고 고정 픽스처 대신 시험마다 새로 만든다(유효기간 1일, 임시 폴더에서 바로 지움).
 * openssl 이 없으면 여기서 던져 TLS 시험이 조용히 빠지지 않고 실패로 드러나게 한다.
 */
function generateSelfSignedCert(): TlsMaterial {
  const dir = mkdtempSync(path.join(tmpdir(), 'wd790-smtp-tls-'));
  try {
    const keyPath = path.join(dir, 'key.pem');
    const certPath = path.join(dir, 'cert.pem');
    execFileSync(
      'openssl',
      [
        'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes',
        '-keyout', keyPath, '-out', certPath, '-days', '1',
        '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1',
      ],
      { stdio: 'ignore' },
    );
    return { key: readFileSync(keyPath, 'utf8'), cert: readFileSync(certPath, 'utf8') };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** sendEmail 의 smtpConfig 타입 — email.ts 가 내보내지 않으므로 함수 시그니처에서 꺼낸다 */
type SmtpConfig = Parameters<typeof sendEmail>[0]['smtpConfig'];

/**
 * 시험들이 공통으로 쓰는 값(127.0.0.1·평문·sender@example.com/pw → dest@example.com)으로 한 통을 보낸다.
 * 시험마다 다른 부분(secure·requireTLS, 제목·본문)만 overrides 로 바꾼다.
 */
function send(
  port: number,
  overrides: { smtp?: Partial<SmtpConfig>; subject?: string; html?: string } = {},
): Promise<void> {
  return sendEmail({
    smtpConfig: { host: '127.0.0.1', port, secure: false, user: 'sender@example.com', pass: 'pw', ...overrides.smtp },
    to: 'dest@example.com',
    subject: overrides.subject ?? 'WD-790 시험',
    html: overrides.html ?? '<p>x</p>',
  });
}

/** AUTH PLAIN 값은 "\0user\0pass" 의 base64 — 서버가 받은 자격증명을 풀어 그대로 넘어왔는지 비교할 수 있게 한다 */
function decodeAuthPlain(received: Received): string {
  return Buffer.from(received.authPlain ?? '', 'base64').toString('utf8');
}

describe('sendEmail — 실제 nodemailer 로 SMTP 전송', () => {
  let close: (() => Promise<void>) | undefined;

  afterEach(async () => {
    await close?.();
    close = undefined;
  });

  it('평문 SMTP 서버에 AUTH PLAIN 으로 로그인해 봉투·제목·본문을 그대로 보낸다', async () => {
    const smtp = await startFakeSmtp();
    close = smtp.close;

    await send(smtp.port, { html: '<p>hello-wd790</p>' });

    // 자격증명이 바뀌지 않고 그대로 넘어가야 한다.
    expect(decodeAuthPlain(smtp.received)).toBe('\0sender@example.com\0pw');
    expect(smtp.received.mailFrom).toContain('sender@example.com');
    expect(smtp.received.rcptTo.join(',')).toContain('dest@example.com');
    // 한글 제목은 MIME 인코딩되므로 본문(html) 표식과 Subject 헤더 존재만 본다.
    expect(smtp.received.data).toMatch(/^Subject: /m);
    expect(smtp.received.data).toContain('hello-wd790');
  });

  it('접속이 거부되면 upstream_error 로 바꿔 던진다', async () => {
    // 띄웠다가 바로 닫은 포트 — 그 포트로의 접속은 ECONNREFUSED 가 난다.
    const smtp = await startFakeSmtp();
    await smtp.close();

    await expect(send(smtp.port)).rejects.toThrow('upstream_error');
  });

  it('requireTLS 인데 서버가 STARTTLS 를 주지 않으면 평문으로 내려가지 않고 upstream_error 로 실패한다', async () => {
    const smtp = await startFakeSmtp();
    close = smtp.close;

    await expect(send(smtp.port, { smtp: { requireTLS: true } })).rejects.toThrow('upstream_error');
    // 자격증명이 평문으로 나가지 않았어야 한다
    expect(smtp.received.authPlain).toBeUndefined();
  });

  /**
   * TLS 경로(STARTTLS·암시적 TLS).
   * email.ts 는 인증서 검증을 끄거나 CA 를 지정하는 옵션을 노출하지 않는다(운영은 공인 인증서를 그대로 검증한다).
   * 그래서 email.ts 가 만든 createTransport 옵션을 그대로 쓰되, 시험용 자체 서명 인증서를 신뢰하도록 tls.ca 만 덧붙인다.
   * 검증을 끄는(rejectUnauthorized: false) 대신 CA 를 주므로 이름(SAN IP)·서명 검증도 실제와 같이 일어난다.
   */
  describe('TLS', () => {
    let material: TlsMaterial;

    beforeAll(() => {
      material = generateSelfSignedCert();
      const realCreateTransport = nodemailer.createTransport.bind(nodemailer);
      vi.spyOn(nodemailer, 'createTransport').mockImplementation(((options: Record<string, unknown>) =>
        realCreateTransport({ ...options, tls: { ca: material.cert } })) as typeof nodemailer.createTransport);
    });

    afterAll(() => {
      vi.restoreAllMocks();
    });

    it('requireTLS 면 STARTTLS 로 승격한 뒤에야 AUTH 를 보내고 메일을 전달한다', async () => {
      const smtp = await startFakeSmtp({ kind: 'starttls', tls: material });
      close = smtp.close;

      await send(smtp.port, { smtp: { requireTLS: true }, subject: 'STARTTLS', html: '<p>hello-starttls</p>' });

      expect(smtp.received.authOverTls).toBe(true);
      expect(decodeAuthPlain(smtp.received)).toBe('\0sender@example.com\0pw');
      expect(smtp.received.data).toContain('hello-starttls');
    });

    it('secure(암시적 TLS) 면 접속 즉시 TLS 로 AUTH·전달한다', async () => {
      const smtp = await startFakeSmtp({ kind: 'implicit', tls: material });
      close = smtp.close;

      await send(smtp.port, { smtp: { secure: true }, subject: 'implicit TLS', html: '<p>hello-implicit</p>' });

      expect(smtp.received.authOverTls).toBe(true);
      expect(smtp.received.data).toContain('hello-implicit');
    });
  });
});
