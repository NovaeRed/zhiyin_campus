import { useState } from 'react';
import { Form, Input, Button, message } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import Icon from './Icon';
import authService from '../services/auth';
import { useAuth } from './authContext';
import { errorMessage } from '../services/api';
import '../styles/Auth.css';
interface Values { student_no: string; username: string; password: string; confirm: string }
export default function AuthPage({ register = false }: { register?: boolean }) {
 const { reload } = useAuth(); const navigate = useNavigate(); const [loading, setLoading] = useState(false);
 async function submit(values: Values) {
  setLoading(true);
  try { if (register) { await authService.register(values.student_no, values.username, values.password); message.success('注册成功，请登录'); navigate('/login'); }
   else { const result = await authService.login(values.student_no, values.password); if (!result.access_token) throw new Error('登录响应不完整'); await reload(); navigate('/'); }
  } catch (e) { message.error(errorMessage(e)); }
  finally { setLoading(false); }
 }
 return <main className="auth-page"><section className="auth-art"><a className="brand" href="/login"><span className="brand-mark"><Icon name="book" size={25} /></span><span>知引校园<small>ZHIYIN CAMPUS</small></span></a><div className="auth-intro"><span>在校园里，遇见更清晰的答案</span><h1>每一次好奇，<br />都有知识回应。</h1><p>课程、学分、奖助政策。<br />你的校园探索，从一个问题开始。</p></div><img src="/campus-garden.png" alt="温暖阳光下的校园图书馆" /><div className="auth-art-footer">YOUR CAMPUS COMPANION <span>探索 · 求知 · 成长</span></div></section><section className="auth-form-area"><div className="auth-form"><span className="auth-kicker">WELCOME TO ZHIYIN</span><h2>{register ? '开启你的校园探索' : '欢迎回来，同学'}</h2><p className="auth-subtitle">{register ? '创建学生账号。管理员及编辑权限由系统负责人分配。' : '登录知引，让校园里的疑问有处可问。'}</p><Form layout="vertical" requiredMark={false} onFinish={submit} size="large">
 <Form.Item name="student_no" label="学号" rules={[{ required: true, whitespace: true, message: '请输入学号' }]}><Input placeholder="请输入你的学号" autoComplete="username" /></Form.Item>
 {register && <Form.Item name="username" label="用户名" rules={[{ required: true, whitespace: true, message: '请输入用户名' }]}><Input placeholder="你希望我们如何称呼你" autoComplete="nickname" /></Form.Item>}
 <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }, ...(register ? [{ pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,}$/, message: '至少8位，包含大小写字母、数字和 @$!%*?& 中的符号' }] : [])]}><Input.Password placeholder="请输入密码" autoComplete={register ? 'new-password' : 'current-password'} /></Form.Item>
 {register && <Form.Item name="confirm" label="确认密码" dependencies={['password']} rules={[{ required: true, message: '请再次输入密码' }, ({ getFieldValue }) => ({ validator(_, value) { return !value || getFieldValue('password') === value ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致')); } })]}><Input.Password placeholder="再次输入密码" autoComplete="new-password" /></Form.Item>}
 <Button type="primary" htmlType="submit" loading={loading} block className="auth-submit">{register ? '创建账号' : '登录校园'}<Icon name="arrow" size={18} /></Button></Form><p className="auth-switch">{register ? '已经有账号？' : '初次来到知引？'}<Link to={register ? '/login' : '/register'}>{register ? '返回登录' : '创建账号'}</Link></p><div className="auth-trust"><Icon name="file" size={15} />基于校园资料 · 回答附有参考来源</div></div><footer>知引校园 · 让知识有迹可循</footer></section></main>;
}
