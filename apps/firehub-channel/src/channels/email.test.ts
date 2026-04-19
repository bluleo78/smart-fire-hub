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
});
