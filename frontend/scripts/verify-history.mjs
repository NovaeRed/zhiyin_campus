import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
assert.ok(process.argv.includes('--live'), 'Explicit --live opt-in required');
const frontend = process.env.PORTAL_UI_BASE_URL || 'http://localhost:5174';
const backend = process.env.PORTAL_UI_BACKEND_URL || 'http://localhost:8081';
const output = path.resolve(import.meta.dirname, '../../target/history-live');
await mkdir(output, { recursive: true });
const suffix = Date.now();
const password = `Aa9!${randomUUID().replaceAll('-', '')}`;
const accounts = []; const checks = []; let browser; let failure; let sessionId;
async function api(url, body, token, expected = 200) {
 const response = await fetch(backend + url, { method: body ? 'POST' : 'GET', headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
 const data = await response.json(); assert.equal(data.code, expected, `${url}: unexpected business status`); return data.data;
}
async function login(page, account) {
 await page.goto(frontend + '/login'); await page.getByLabel('学号', { exact: true }).fill(account.studentNo); await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录校园' }).click(); await page.getByRole('heading', { name: /校园里的疑问/ }).waitFor();
}
try {
 for (const label of ['a', 'b']) {
  const studentNo = `history_${label}_${suffix}`;
  const result = await api('/auth/register', { student_no: studentNo, username: `历史隔离验收${label}`, password }, undefined, 201);
  accounts.push({ studentNo, token: result.access_token });
 }
 browser = await chromium.launch({ channel: 'msedge', headless: true });
 const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
 const errors = []; page.on('pageerror', error => errors.push(error.message));
 await login(page, accounts[0]);
 await page.getByRole('link', { name: '历史对话', exact: true }).click();
 await page.getByText('还没有历史对话，从一个校园问题开始吧', { exact: true }).waitFor(); checks.push('New account has no fabricated history cards');
 const question = `2099级计算机科学与技术专业培养方案是什么？历史验证${suffix}`;
 const answer = await api('/rag/ask', { question }, accounts[0].token); sessionId = answer.sessionId;
 const sessions = await api('/rag/sessions', undefined, accounts[0].token);
 assert.equal(sessions.total, 1); assert.equal(sessions.items[0].id, sessionId); assert.equal(sessions.items[0].title, question); checks.push('Session list returns the newly persisted question and actual session ID');
 await page.reload(); await page.getByRole('heading', { name: question, exact: true }).waitFor();
 const card = page.locator('.history-card'); assert.equal(await card.count(), 1); assert.equal(await card.getAttribute('href'), `/?session=${sessionId}`); checks.push('Refreshing history discovers data written after the page loaded');
 await page.screenshot({ path: path.join(output, 'real-history-list.png'), fullPage: true });
 await card.click(); await page.locator('.chat-message.user .answer-text').filter({ hasText: question }).waitFor();
 assert.equal(await page.locator('.chat-message.bot .answer-text').innerText(), answer.answer); checks.push('Opened history restores the stored question and actual answer');
 await page.reload(); await page.locator('.chat-message.user .answer-text').filter({ hasText: question }).waitFor(); checks.push('Conversation detail survives a browser reload');
 const history = await api(`/rag/sessions/${sessionId}/queries`, undefined, accounts[0].token);
 assert.equal(history.total, 1); assert.equal(history.items[0].queryId, answer.queryId); assert.equal(history.items[0].question, question); assert.equal(history.items[0].answer, answer.answer); checks.push('Detail response matches the query ID and answer returned by the real ask API');
 await page.getByRole('button', { name: '退出登录', exact: true }).click(); await login(page, accounts[1]);
 await page.getByRole('link', { name: '历史对话', exact: true }).click(); await page.getByText('还没有历史对话，从一个校园问题开始吧', { exact: true }).waitFor(); checks.push('Second account cannot see the first account history list');
 await api(`/rag/sessions/${sessionId}/queries`, undefined, accounts[1].token, 404); checks.push('Server rejects another user reading the session directly');
 await page.goto(frontend + `/?session=${sessionId}`); await page.getByText('无法读取会话', { exact: true }).waitFor(); assert.equal(await page.locator('.chat-message').count(), 0); checks.push('Direct foreign session URL displays an error without fallback sample messages');
 assert.deepEqual(errors, []); checks.push('No uncaught browser errors');
 await page.goto(frontend + '/'); await page.getByRole('button', { name: '退出登录', exact: true }).click();
} catch (error) { failure = error.message; throw error; }
finally {
 if (browser) await browser.close();
 for (const account of accounts) await api('/auth/logout', {}, account.token, 204);
 await writeFile(path.join(output, 'result.json'), JSON.stringify({ frontend, backend, sessionId, checks, failure: failure || null }, null, 2));
}
console.log(JSON.stringify({ passed: checks.length, sessionId, output }));
