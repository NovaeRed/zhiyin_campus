import api, { clearTokens, saveTokens } from './api';
const register = async (student_no: string, username: string, password: string) => {
  const response = await api.post('/auth/register', { student_no, username, password });
  return response.data.data;
};
const login = async (student_no: string, password: string) => {
  const response = await api.post('/auth/login', { student_no, password });
  saveTokens(response.data.data);
  return response.data.data;
};
const logout = async () => {
  try {
    await api.post('/auth/logout');
  } finally {
    clearTokens();
  }
};
export default { register, login, logout };
