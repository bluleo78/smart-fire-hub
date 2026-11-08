import nodemailer from 'nodemailer';

interface SmtpConfig {
  host: string;
  port: number;
  /** 암시적 TLS(접속 즉시 TLS, 관례상 465 포트) 여부 — STARTTLS 가 아니다. */
  secure: boolean;
  /**
   * STARTTLS 승격 강제 여부(WD-593). true 면 서버가 STARTTLS 를 주지 않을 때 평문으로 내려가지 않고 실패한다.
   * 이 필드를 보내지 않는 옛 api 와의 호환을 위해 선택 필드로 둔다.
   */
  requireTLS?: boolean;
  user: string;
  pass: string;
}

interface SendEmailParams {
  smtpConfig: SmtpConfig;
  to: string;
  subject: string;
  html: string;
}

export async function sendEmail(params: SendEmailParams): Promise<void> {
  const transporter = nodemailer.createTransport({
    host: params.smtpConfig.host,
    port: params.smtpConfig.port,
    secure: params.smtpConfig.secure,
    // 옛 api 는 requireTLS 를 보내지 않으므로 없으면 false(강제하지 않음)로 둬 기존 동작을 유지한다.
    requireTLS: params.smtpConfig.requireTLS ?? false,
    auth: { user: params.smtpConfig.user, pass: params.smtpConfig.pass },
  });

  try {
    await transporter.sendMail({
      from: params.smtpConfig.user,
      to: params.to,
      subject: params.subject,
      html: params.html,
    });
  } catch {
    throw new Error('upstream_error');
  }
}
