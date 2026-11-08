import { describe, it, expect, vi, beforeEach } from 'vitest';
import { sendEmail } from './email.js';

vi.mock('nodemailer', () => ({
  default: {
    createTransport: vi.fn(() => ({
      sendMail: vi.fn().mockResolvedValue({ messageId: 'test-id' }),
    })),
  },
}));

describe('sendEmail', () => {
  it('SMTP 전송 성공', async () => {
    await expect(sendEmail({
      smtpConfig: { host: 'smtp.test.com', port: 587, secure: false, user: 'u', pass: 'p' },
      to: 'dest@example.com',
      subject: '테스트',
      html: '<p>내용</p>',
    })).resolves.toBeUndefined();
  });

  it('SMTP 오류 → upstream_error throw', async () => {
    const nodemailer = await import('nodemailer');
    vi.mocked(nodemailer.default.createTransport).mockReturnValueOnce({
      sendMail: vi.fn().mockRejectedValue(new Error('ECONNREFUSED')),
    } as never);

    await expect(sendEmail({
      smtpConfig: { host: 'bad-host', port: 587, secure: false, user: 'u', pass: 'p' },
      to: 'dest@example.com',
      subject: '실패',
      html: '<p>내용</p>',
    })).rejects.toThrow('upstream_error');
  });

  // WD-593: STARTTLS 강제(requireTLS)가 nodemailer 로 그대로 전달돼야 서버가 STARTTLS 를 주지 않을 때 평문으로 내려가지 않는다.
  it('requireTLS true 는 createTransport 로 그대로 전달된다', async () => {
    const nodemailer = await import('nodemailer');
    const createTransport = vi.mocked(nodemailer.default.createTransport);
    createTransport.mockClear();

    await sendEmail({
      smtpConfig: { host: 'smtp.test.com', port: 587, secure: false, requireTLS: true, user: 'u', pass: 'p' },
      to: 'dest@example.com',
      subject: '테스트',
      html: '<p>내용</p>',
    });

    expect(createTransport).toHaveBeenCalledWith(
      expect.objectContaining({ port: 587, secure: false, requireTLS: true }),
    );
  });

  // WD-593: requireTLS 를 보내지 않는 옛 api 와의 호환 — 없으면 false 로 본다(기존 동작 유지).
  it('requireTLS 가 없으면 false 로 createTransport 에 전달된다', async () => {
    const nodemailer = await import('nodemailer');
    const createTransport = vi.mocked(nodemailer.default.createTransport);
    createTransport.mockClear();

    await sendEmail({
      smtpConfig: { host: 'smtp.test.com', port: 587, secure: false, user: 'u', pass: 'p' },
      to: 'dest@example.com',
      subject: '테스트',
      html: '<p>내용</p>',
    });

    expect(createTransport).toHaveBeenCalledWith(
      expect.objectContaining({ secure: false, requireTLS: false }),
    );
  });
});
