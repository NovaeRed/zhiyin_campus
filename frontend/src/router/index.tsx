import { createBrowserRouter } from 'react-router-dom';
import LoginPage from '../pages/Login';
import RegisterPage from '../pages/Register';
import ChatRoute from '../components/ChatRoute';
import DocumentsPage from '../pages/Documents';
import HistoryPage from '../pages/History';
import RequireAccount from '../components/RequireAccount';
import { Result } from 'antd';
const router = createBrowserRouter([
 { path: '/', element: <RequireAccount><ChatRoute /></RequireAccount> },
 { path: '/history', element: <RequireAccount><HistoryPage /></RequireAccount> },
 { path: '/admin/documents', element: <RequireAccount manager><DocumentsPage /></RequireAccount> },
 { path: '/login', element: <LoginPage /> },
 { path: '/register', element: <RegisterPage /> },
 { path: '*', element: <Result status="404" title="页面不存在" extra={<a href="/">返回校园问答</a>} /> },
]);
export default router;
