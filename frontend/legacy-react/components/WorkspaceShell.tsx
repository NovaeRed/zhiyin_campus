import type { ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { message } from 'antd';
import Icon from './Icon';
import { useAuth } from './authContext';
import authService from '../services/auth';
import '../styles/Chat.css';
import '../styles/Portal.css';
export default function WorkspaceShell({ children, title, onNew, busy = false, chatLayout = false }: { children: ReactNode; title: string; onNew?: () => void; busy?: boolean; chatLayout?: boolean }) {
 const { account, reload } = useAuth(); const navigate = useNavigate(); const location = useLocation();
 async function logout() { try { await authService.logout(); } catch { message.info('已清除本地登录状态'); } await reload(); navigate('/login'); }
 return <div className={chatLayout ? 'workspace chat-workspace' : 'workspace'}><aside className="sidebar"><Link className="brand" to="/"><span className="brand-mark"><Icon name="book" size={25} /></span><span>知引校园<small>ZHIYIN CAMPUS</small></span></Link><button className="new-chat" disabled={busy} onClick={() => onNew ? onNew() : navigate('/')}><Icon name="plus" />开启新对话</button><div className="nav-caption">我的工作台</div><nav className="portal-nav"><Link className={location.pathname === '/' ? 'nav-active' : 'nav-item'} to="/"><Icon name="chat" />校园问答</Link><Link className={location.pathname === '/history' ? 'nav-active' : 'nav-item'} to="/history"><Icon name="file" />历史对话</Link>{account?.canManageDocuments && <Link className={location.pathname.startsWith('/admin') ? 'nav-active' : 'nav-item'} to="/admin/documents"><Icon name="upload" />知识库管理</Link>}</nav><div className="sidebar-note"><span className="note-label">一点提问小建议</span><p>带上专业和入学年份，<br />让答案更贴近你的校园生活。</p><span>例如：2024级计算机专业</span></div><div className="profile"><span className="profile-avatar">{account?.username.slice(0, 1)}</span><div>{account?.username}<small>{account?.canManageDocuments ? '知识库维护人员' : `学号 ${account?.studentNo}`}</small></div><button onClick={() => void logout()} aria-label="退出登录"><Icon name="logout" size={18} /></button></div></aside><main className="main-panel" aria-label={title}>{children}<footer className="workspace-footer">知引校园<span>让知识有迹可循，让校园生活从容一点。</span></footer></main></div>;
}
