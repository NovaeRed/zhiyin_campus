import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
assert.ok(process.argv.includes('--live'), 'Explicit --live opt-in required: this test creates accounts and calls real APIs');
assert.ok(process.env.PORTAL_TEST_JAVA_CLASSPATH, 'Set PORTAL_TEST_JAVA_CLASSPATH to the local PostgreSQL and SnakeYAML JARs');
const root = path.resolve(import.meta.dirname, '../..');
const output = path.resolve(root, process.env.PORTAL_UI_OUTPUT || 'target/frontend-ui-8081'); await mkdir(output, { recursive: true });
const base = process.env.PORTAL_UI_BASE_URL || 'http://localhost:5174'; const backend = process.env.PORTAL_UI_BACKEND_URL || 'http://localhost:8081';
const student = `portal_ui_${Date.now()}`; const password = `Aa9!${randomUUID().replaceAll('-', '')}`;
const checks = []; let token; let accountId; let documentId; let browser; let page; let failure;
async function api(url, body, method = 'GET') {
 const response = await fetch(backend + url, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
 const parsed = await response.json(); assert.ok(parsed.code < 400, `${method} ${url}: ${parsed.msg}`); return parsed.data;
}
function role(value) {
 const driver = process.env.PORTAL_TEST_JAVA_CLASSPATH; assert.ok(driver, 'PORTAL_TEST_PG_DRIVER is required');
 const result = spawnSync('java', ['--class-path', driver, path.join(import.meta.dirname, 'PortalUiUser.java'), String(accountId), student, value, root], { encoding: 'utf8', windowsHide: true });
 assert.equal(result.status, 0, 'UI fixture role update failed');
}
async function screenshot(page, name) { await page.waitForFunction(() => !document.querySelector('.ant-spin-spinning')); await page.screenshot({ path: path.join(output, name), fullPage: true, animations: 'disabled' }); }
async function fits(page, label) { await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth + 1, undefined, { timeout: 5000 }).catch(() => {}); const valid = await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1); if (!valid) { await screenshot(page, 'overflow-debug.png'); console.log(await page.evaluate(() => [...document.querySelectorAll('body *')].map(el => ({ tag: el.tagName, cls: el.className, right: Math.round(el.getBoundingClientRect().right), width: Math.round(el.getBoundingClientRect().width) })).filter(el => el.right > innerWidth + 2).slice(-20))); } assert.ok(valid, `${label}: horizontal overflow`); checks.push(`${label}: no horizontal overflow`); }
try {
 const registered = await api('/auth/register', { student_no: student, username: '界面验收同学', password }, 'POST'); token = registered.access_token;
 accountId = (await api('/account/me')).id;
 browser = await chromium.launch({ channel: 'msedge', headless: true });
 page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
 const errors = []; const apiOrigins = new Set(); page.on('pageerror', error => errors.push(error.message)); page.on('request', request => { const url = new URL(request.url()); if (/^\/(?:api\/)?(auth|account|rag|admin)\//.test(url.pathname) && request.resourceType() !== 'document') apiOrigins.add(url.origin); });
 await page.goto(base + '/register');
 await page.getByLabel('学号', { exact: true }).fill(student + '_reg');
 await page.getByLabel('用户名', { exact: true }).fill('注册校验');
 await page.getByLabel('密码', { exact: true }).fill('weak');
 await page.getByLabel('确认密码', { exact: true }).fill('different');
 await page.getByRole('button', { name: '创建账号', exact: true }).click();
 await page.getByText('两次输入的密码不一致', { exact: true }).waitFor();
 assert.ok(await page.getByText('至少8位', { exact: false }).isVisible()); checks.push('Registration password strength and confirmation validation');
 await page.goto(base + '/login'); await page.getByRole('heading', { name: '欢迎回来，同学' }).waitFor(); await screenshot(page, 'login-desktop.png');
 await page.getByLabel('学号', { exact: true }).fill(student); await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录校园' }).click();
 await page.getByRole('heading', { name: /校园里的疑问/ }).waitFor(); checks.push('Real UI login and account navigation');
 assert.equal(await page.getByRole('link', { name: '知识库管理' }).count(), 0); checks.push('Student navigation hides document management');
 await screenshot(page, 'chat-desktop.png'); await fits(page, 'Chat 1440px');
 await page.getByLabel('你的校园问题').fill('2099级计算机科学与技术专业的培养方案是什么？'); await page.getByRole('button', { name: '发送问题' }).click(); await page.getByText('根据现有资料无法回答。', { exact: true }).waitFor();
 await page.getByRole('link', { name: '历史对话', exact: true }).click(); await page.getByText('查看并继续对话').first().waitFor(); await screenshot(page, 'history-desktop.png'); await page.getByText('查看并继续对话').first().click();
 await page.getByText('根据现有资料无法回答。', { exact: true }).waitFor(); await page.reload(); await page.getByText('根据现有资料无法回答。', { exact: true }).waitFor(); checks.push('Ask, persisted history, reopen and refresh restore');
 await page.getByLabel('专业', { exact: true }).fill('计算机科学与技术');
 await page.getByLabel('入学年份', { exact: true }).fill('2024');
 await page.getByLabel('你的校园问题').fill('2024级计算机科学与技术专业大三有哪些课程？');
 const courseResponse = page.waitForResponse(response => response.url().endsWith('/rag/ask') && response.request().method() === 'POST');
 await page.getByRole('button', { name: '发送问题' }).click();
 const course = await (await courseResponse).json(); assert.ok(course.data.sources.length > 0);
 await page.locator('.sources details').first().waitFor(); await page.locator('.sources summary').first().click();
 assert.ok(await page.locator('.sources details[open] p').first().isVisible()); checks.push('Real course query and expanded citation text');
 await page.getByRole('button', { name: '开启新对话', exact: true }).click();
 await page.getByLabel('你的校园问题').fill('第一行'); await page.getByLabel('你的校园问题').press('Shift+Enter');
 assert.equal(await page.getByLabel('你的校园问题').inputValue(), '第一行\n'); assert.equal(await page.locator('.chat-message').count(), 0); checks.push('Shift+Enter adds newline without submitting');

 const offlineQuestion = '网络恢复后待发送的问题';
 await page.getByLabel('你的校园问题').fill(offlineQuestion); await page.context().setOffline(true);
 await page.getByRole('button', { name: '发送问题' }).click();
 await page.getByLabel('你的校园问题').waitFor();
 await page.waitForFunction(value => document.querySelector('textarea')?.value === value, offlineQuestion);
 assert.equal(await page.locator('.chat-message.user').count(), 0, 'Failed request must not leave a duplicate user message before retry');
 await page.context().setOffline(false); checks.push('Real offline send preserves draft without duplicate message');
 await page.goto(base + '/admin/documents'); await page.getByText('没有文档管理权限', { exact: true }).waitFor(); checks.push('Direct administrator URL rejected for student');
 role('admin'); await page.reload(); await page.getByRole('heading', { name: '让校园知识，有序生长。' }).waitFor(); await page.locator('.ant-table-row').first().waitFor(); await screenshot(page, 'documents-desktop.png'); await fits(page, 'Documents 1440px');
 await page.getByRole('button', { name: '添加文档', exact: true }).click(); await page.getByLabel('文档标题', { exact: true }).waitFor();
 await page.locator('input[type=file]').setInputFiles({ name: 'unsupported.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('unsupported') });
 await page.getByText('暂不支持此文件格式', { exact: true }).waitFor();
 await page.locator('input[type=file]').setInputFiles({ name: 'empty.txt', mimeType: 'text/plain', buffer: Buffer.alloc(0) });
 await page.getByText('文件应大于0字节且不超过20MB', { exact: true }).waitFor();
 checks.push('Upload rejects unsupported and empty files without submitting');
 await page.locator('input[type=file]').setInputFiles({ name: '校园门户浏览器验收.txt', mimeType: 'text/plain', buffer: Buffer.from('校园门户浏览器验收专用资料，仅用于验证管理员上传表单和停用操作，不属于正式学校政策。') });
 const reuse = Number(process.env.PORTAL_UI_REUSE_DOC || 0);
 const existing = reuse ? await api(`/admin/documents/${reuse}`) : null;
 if (existing) assert.ok(existing.title.startsWith('浏览器文档验收-'), 'Only browser acceptance documents may be reused');
 const title = existing?.title || `浏览器文档验收-${Date.now()}`;
 await page.getByLabel('文档标题', { exact: true }).fill(title); await page.getByLabel('适用专业').fill('接口验收隔离专业'); await page.getByLabel('入学年份').fill('2199'); await page.getByLabel('发布部门').fill('界面验收');
 await page.getByLabel('文档标题', { exact: true }).fill(''); await page.getByRole('button', { name: '上传并入库' }).click();
 await page.getByText('请输入文档标题', { exact: true }).waitFor();
 await page.getByLabel('文档标题', { exact: true }).fill(title); checks.push('Upload requires document title');
 await screenshot(page, 'upload-desktop.png');
 if (!reuse) {
 const uploaded = page.waitForResponse(response => response.url().endsWith('/rag/documents') && response.request().method() === 'POST');
 await page.getByRole('button', { name: '上传并入库' }).click(); const result = await (await uploaded).json(); assert.equal(result.code, 201); documentId = result.data.docId; await page.getByText('文档已成功入库', { exact: true }).waitFor(); checks.push('Real browser file upload and metadata form');
 } else { documentId = reuse; await api(`/admin/documents/${reuse}/status`, { status: 'active' }, 'PUT'); checks.push(`Reused test document ${reuse}: browser upload verified in prior run`); }
 await page.locator('.ant-drawer-open .ant-drawer-close').click();
 await page.getByLabel('搜索文档标题').fill(title); await page.getByLabel('搜索文档标题').press('Enter'); await page.getByRole('button', { name: '详情', exact: true }).first().waitFor();
 await page.getByRole('button', { name: '详情', exact: true }).first().click(); await page.getByRole('tab', { name: '切片正文', exact: true }).click(); await page.locator('.chunk-preview summary').first().click(); await screenshot(page, 'document-detail.png');
 await page.locator('.ant-drawer-open .ant-drawer-close').click(); await page.getByRole('button', { name: '停用', exact: true }).first().click(); await page.getByRole('button', { name: /确\s*定/ }).click(); await page.getByText('已停用', { exact: true }).waitFor(); checks.push('Document search, chunk preview and UI deactivation');
 for (const width of [375, 768]) {
  await page.setViewportSize({ width, height: 900 }); await fits(page, `Documents ${width}px`); await screenshot(page, `documents-${width}.png`);
  await page.getByRole('button', { name: '添加文档', exact: true }).click(); await fits(page, `Upload ${width}px`); await screenshot(page, `upload-${width}.png`); await page.locator('.ant-drawer-open .ant-drawer-close').click();
  await page.goto(base + '/'); await page.getByRole('heading', { name: /校园里的疑问/ }).waitFor(); await fits(page, `Chat ${width}px`); await screenshot(page, `chat-${width}.png`); await page.goto(base + '/admin/documents'); await page.getByRole('button', { name: '添加文档', exact: true }).waitFor();
 }
 await page.getByRole('button', { name: '退出登录', exact: true }).click();
 await page.getByRole('heading', { name: '欢迎回来，同学' }).waitFor();
 assert.equal(await page.evaluate(() => localStorage.getItem('token')), null);
 assert.equal(await page.evaluate(() => localStorage.getItem('refresh_token')), null);
 await page.goto(base + '/admin/documents'); await page.getByRole('heading', { name: '欢迎回来，同学' }).waitFor(); checks.push('Real logout clears tokens and protected routes return to login');
 for (const width of [375, 768]) {
  await page.setViewportSize({ width, height: 900 }); await fits(page, `Login ${width}px`); await screenshot(page, `login-${width}.png`);
  await page.goto(base + '/register'); await page.getByRole('heading', { name: '开启你的校园探索' }).waitFor(); await fits(page, `Register ${width}px`); await screenshot(page, `register-${width}.png`); await page.goto(base + '/login');
 }
 assert.deepEqual([...apiOrigins], [new URL(base).origin], 'Browser API requests must use the local development proxy'); checks.push('Browser requests use same-origin proxy configured for 8081');
 assert.deepEqual(errors, []); checks.push('No uncaught browser errors');
} catch (error) {
 failure = error.message;
 if (page) await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }).catch(() => {});
 throw error;
} finally {
 if (documentId) { try { await api(`/admin/documents/${documentId}/status`, { status: 'deprecated' }, 'PUT'); } catch { checks.push('WARNING: test document cleanup needs attention'); } }
 if (accountId) role('student');
 if (token) await api('/auth/logout', {}, 'POST');
 if (browser) await browser.close();
 await writeFile(path.join(output, 'result.json'), JSON.stringify({ checks, testDocumentId: documentId || null, frontend: base, backend, failure: failure || null }, null, 2));
}
console.log(JSON.stringify({ passed: checks.length, testDocumentId: documentId, output }));
