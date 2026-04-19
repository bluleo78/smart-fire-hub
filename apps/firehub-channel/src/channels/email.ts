import nodemailer from 'nodemailer';

interface SmtpConfig {
  host: string;
  port: number;
  secure: boolean;
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
