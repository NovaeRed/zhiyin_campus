import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { Button, Result, Spin } from 'antd';
import { useAuth } from './authContext';
export default function RequireAccount({ children, manager = false }: { children: ReactNode; manager?: boolean }) {
 const { account, loading, error, reload } = useAuth();
 if (loading) return <div className="route-loading"><Spin description="正在验证登录状态…" /></div>;
 if (!localStorage.getItem('token')) return <Navigate to="/login" replace />;
 if (!account) return <Result status="warning" title="暂时无法获取账号信息" subTitle={error} extra={<Button onClick={() => void reload()}>重试</Button>} />;
 if (manager && !account.canManageDocuments) return <Result status="403" title="没有文档管理权限" subTitle="此页面仅供管理员和知识编辑人员使用。" extra={<a href="/">返回校园问答</a>} />;
 return children;
}
