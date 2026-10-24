<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import { useAuth } from '../composables/auth';
import authService from '../services/auth';
import Icon from './Icon.vue';
import '../styles/Chat.css';
import '../styles/Portal.css';
defineProps<{ title: string; busy?: boolean; chatLayout?: boolean }>();
const emit = defineEmits<{ new: [] }>();
const route = useRoute(), router = useRouter(), { account, error, reload } = useAuth();
async function logout() { try { await authService.logout(); } catch { message.info('已清除本地登录状态'); } await reload(); await router.push('/login'); }
function newChat() { if (route.path === '/') emit('new'); else void router.push('/'); }
</script>
<template>
 <div :class="['workspace', { 'chat-workspace': chatLayout }]"><aside class="sidebar">
  <RouterLink class="brand" to="/"><span class="brand-mark"><Icon name="book" :size="25" /></span><span>知引校园<small>ZHIYIN CAMPUS</small></span></RouterLink>
  <button class="new-chat" :disabled="busy" @click="newChat"><Icon name="plus" />开启新对话</button><div class="nav-caption">我的工作台</div>
  <nav class="portal-nav"><RouterLink :class="route.path === '/' ? 'nav-active' : 'nav-item'" to="/"><Icon name="chat" />校园问答</RouterLink><RouterLink :class="route.path === '/history' ? 'nav-active' : 'nav-item'" to="/history"><Icon name="file" />历史对话</RouterLink><RouterLink v-if="account?.canManageDocuments" :class="route.path.startsWith('/admin') ? 'nav-active' : 'nav-item'" to="/admin/documents"><Icon name="upload" />知识库管理</RouterLink></nav>
  <div class="sidebar-note"><span class="note-label">一点提问小建议</span><p>带上专业和入学年份，<br />让答案更贴近你的校园生活。</p><span>例如：2024级计算机专业</span></div>
  <div class="profile"><span class="profile-avatar">{{ account?.username.slice(0, 1) }}</span><div>{{ account?.username }}<small>{{ account?.canManageDocuments ? '知识库维护人员' : `学号 ${account?.studentNo || ''}` }}</small></div><button aria-label="退出登录" @click="logout"><Icon name="logout" :size="18" /></button></div>
 </aside><main class="main-panel" :aria-label="title">
  <a-result v-if="!account" status="warning" title="暂时无法获取账号信息" :sub-title="error"><template #extra><a-button @click="reload">重试</a-button></template></a-result>
  <a-result v-else-if="route.meta.manager && !account.canManageDocuments" status="403" title="没有文档管理权限" sub-title="此页面仅供管理员和知识编辑人员使用。"><template #extra><RouterLink to="/">返回校园问答</RouterLink></template></a-result>
  <slot v-else /><footer class="workspace-footer">知引校园<span>让知识有迹可循，让校园生活从容一点。</span></footer>
 </main></div>
</template>
