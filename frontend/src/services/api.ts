import axios from 'axios';
const baseURL = import.meta.env.VITE_API_BASE_URL || '/api';
export class ApiError extends Error {
  code: number;
  constructor(text: string, code: number) {
    super(text);
    this.code = code;
  }
}
export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请稍后重试';
}
export function clearTokens() {
  localStorage.removeItem('token');
  localStorage.removeItem('refresh_token');
}
export function saveTokens(data: { access_token: string; refresh_token: string }) {
  localStorage.setItem('token', data.access_token);
  localStorage.setItem('refresh_token', data.refresh_token);
}
const api = axios.create({ baseURL });
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});
let refreshing: Promise<void> | null = null;
async function renew() {
  const refreshToken = localStorage.getItem('refresh_token');
  if (!refreshToken) throw new ApiError('登录已过期，请重新登录', 401);
  const response = await axios.post(
    `${baseURL}/auth/refresh`,
    { refresh_token: refreshToken },
    { headers: { Authorization: `Bearer ${localStorage.getItem('token')}` } },
  );
  if (response.data.code >= 400) throw new ApiError(response.data.msg, response.data.code);
  saveTokens(response.data.data);
}
api.interceptors.response.use(async (response) => {
  if (response.data && typeof response.data.code === 'number' && response.data.code >= 400) {
    throw Object.assign(new ApiError(response.data.msg || '请求失败', response.data.code), {
      config: response.config,
    });
  }
  return response;
});
api.interceptors.response.use(undefined, async (error) => {
  const code = error instanceof ApiError ? error.code : error.response?.status;
  const config = error.config;
  if (code === 401 && config && !config.url?.startsWith('/auth/') && !config._retried) {
    config._retried = true;
    try {
      if (!refreshing)
        refreshing = renew().finally(() => {
          refreshing = null;
        });
      await refreshing;
    } catch {
      clearTokens();
      window.dispatchEvent(new Event('auth-expired'));
      throw new ApiError('登录已过期，请重新登录', 401);
    }
    return api(config);
  }
  throw new ApiError(error.response?.data?.msg || error.message || '无法连接服务器', code || 0);
});
export default api;
