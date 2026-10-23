import { useEffect, useState } from 'react';
import { Alert, Button, Space, Spin } from 'antd';
import { getDocumentOriginal } from '../services/portal';
import type { DocumentRecord } from '../services/portal';
import { errorMessage } from '../services/api';

export default function DocumentOriginal({ document }: { document: DocumentRecord }) {
 const [source, setSource] = useState<{ url: string; text?: string; filename: string }>();
 const [loading, setLoading] = useState(true), [error, setError] = useState(''), [revision, setRevision] = useState(0);
 // The request owns its object URL; closing/changing the document always revokes it.
 useEffect(() => {
  let active = true, url: string | undefined;
  // eslint-disable-next-line react-hooks/set-state-in-effect
  setLoading(true); setError(''); setSource(undefined);
  getDocumentOriginal(document.id).then(async response => {
   const text = ['txt', 'md', 'html'].includes(document.fileType.toLowerCase()) ? await response.data.text() : undefined;
   if (!active) return;
   url = URL.createObjectURL(response.data);
   setSource({ url, text, filename: `${document.title}.${document.fileType}` });
  }).catch(e => { if (active) setError(errorMessage(e)); }).finally(() => { if (active) setLoading(false); });
  return () => { active = false; if (url) URL.revokeObjectURL(url); };
 }, [document.id, document.title, document.fileType, revision]);
 if (loading) return <div className="original-loading"><Spin description="正在读取原始文档…" /></div>;
 if (error) return <Alert type="error" showIcon title="原始文件加载失败" description={error} action={<Button onClick={() => setRevision(v => v + 1)}>重试</Button>} />;
 if (!source) return null;
 const type = document.fileType.toLowerCase();
 return <section className="document-original" aria-label="原始文档预览">
  <Space wrap className="original-toolbar"><Button href={source.url} download={source.filename}>下载原始文件</Button><span className="muted-text">{type.toUpperCase()} · 上传时保留的原始文件</span></Space>
  {type === 'pdf' ? <><p className="form-helper">若浏览器无法显示 PDF，可下载后查看。</p><iframe title="原始PDF文档" className="original-frame" src={source.url} /></>
   : type === 'html' ? <iframe title="原始HTML文档" className="original-frame" sandbox="" referrerPolicy="no-referrer" srcDoc={`<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:">${source.text || ''}`} />
   : source.text !== undefined ? <pre className="original-text">{source.text}</pre>
   : <Alert type="info" showIcon title="此格式支持下载查看" description="请下载原始文件，使用本地文档或表格应用打开。" />}
 </section>;
}
