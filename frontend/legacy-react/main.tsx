import React from 'react'
import ReactDOM from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import router from './router'
import AuthProvider from './components/AuthProvider'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import 'antd/dist/reset.css';
import './index.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#2d5a45', borderRadius: 9, fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif' } }}>
      <AuthProvider><RouterProvider router={router} /></AuthProvider>
    </ConfigProvider>
  </React.StrictMode>,
)
