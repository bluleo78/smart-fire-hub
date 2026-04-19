import axios, { AxiosError } from 'axios';

interface SendKakaoParams {
  accessToken: string;
  text: string;
}

export async function sendKakaoMessage(params: SendKakaoParams): Promise<void> {
  const templateObject = JSON.stringify({
    object_type: 'text',
    text: params.text,
    link: { web_url: '', mobile_web_url: '' },
  });

  try {
    await axios.post(
      'https://kapi.kakao.com/v2/api/talk/memo/default/send',
      new URLSearchParams({ template_object: templateObject }),
      {
        headers: {
          Authorization: `Bearer ${params.accessToken}`,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
      },
    );
  } catch (err) {
    const status = (err as AxiosError).response?.status;
    if (status === 401) throw new Error('auth_error');
    throw new Error('upstream_error');
  }
}
