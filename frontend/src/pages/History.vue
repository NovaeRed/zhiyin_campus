<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { Modal, message } from 'ant-design-vue';
import WorkspaceShell from '../components/WorkspaceShell.vue';
import Icon from '../components/Icon.vue';
import { getSessions, deleteSession, deleteSessions, dateText } from '../services/portal';
import type { Page, SessionRecord } from '../services/portal';
import { errorMessage } from '../services/api';
const page = ref(1), data = ref<Page<SessionRecord>>(), error = ref(''), loading = ref(true), retry = ref(0), selected = ref<number[]>([]), deleting = ref(false);
watch([page, retry], async (_values, _old, onCleanup) => { let active = true; onCleanup(() => { active = false; }); loading.value = true; error.value = '';
 try { const result = await getSessions(page.value); if (active) { data.value = result; selected.value = []; if (!result.items.length && page.value > 1) page.value = Math.max(1, Math.ceil(result.total / 12)); } }
 catch (e) { if (active) error.value = errorMessage(e); } finally { if (active) loading.value = false; }
}, { immediate: true });
const allSelected = computed(() => !!data.value?.items.length && data.value.items.every(s => selected.value.includes(s.id)));
function confirmDelete(ids: number[]) { if (!ids.length || deleting.value) return; Modal.confirm({ title: ids.length === 1 ? '删除这条对话？' : `删除选中的 ${ids.length} 条对话？`, content: '删除后将无法再查看或继续这些对话。', okText: '确认删除', cancelText: '取消', okButtonProps: { danger: true }, onOk: async () => {
 deleting.value = true; try { const result = ids.length === 1 ? await deleteSession(ids[0]!) : await deleteSessions(ids); page.value = Math.min(page.value, Math.max(1, Math.ceil(Math.max(0, (data.value?.total || 0) - result.deletedCount) / 12))); selected.value = []; retry.value++; message.success('对话已删除'); }
 catch (e) { message.error(errorMessage(e)); throw e; } finally { deleting.value = false; }
} }); }
function select(id: number, checked: boolean) { selected.value = checked ? [...selected.value, id] : selected.value.filter(value => value !== id); }
</script>
<template><WorkspaceShell title="历史对话"><div class="portal-content"><div class="page-heading"><div><span class="eyebrow">YOUR KNOWLEDGE JOURNEY</span><h1>每一个问题，都留有足迹。</h1><p>找回之前的问答、参考来源，也可以继续探索。</p></div></div>
 <a-alert v-if="error" type="error" :message="error"><template #action><a-button @click="retry++">重试</a-button></template></a-alert><a-spin v-else-if="loading" />
 <template v-else-if="data?.items.length"><div class="history-toolbar" aria-label="对话管理"><a-checkbox :checked="allSelected" :indeterminate="!!selected.length && !allSelected" :disabled="deleting" @change="selected = $event.target.checked ? data!.items.map(s => s.id) : []">全选本页</a-checkbox><span>已选 {{ selected.length }} 条</span><a-button v-if="selected.length" type="text" :disabled="deleting" @click="selected = []">取消选择</a-button><a-button danger :loading="deleting" :disabled="!selected.length" @click="confirmDelete([...selected])">删除所选{{ selected.length ? `（${selected.length}）` : '' }}</a-button></div>
 <div class="history-grid"><article v-for="session in data.items" :key="session.id" :class="['history-entry', { 'is-selected': selected.includes(session.id) }]"><div class="history-card-tools"><a-checkbox :aria-label="`选择对话：${session.title}`" :checked="selected.includes(session.id)" :disabled="deleting" @change="select(session.id, $event.target.checked)">选择</a-checkbox><a-button type="text" danger size="small" :aria-label="`删除对话：${session.title}`" :disabled="deleting" @click="confirmDelete([session.id])">删除</a-button></div><RouterLink class="history-card" :to="`/?session=${session.id}`"><span class="topic-icon"><Icon name="chat" /></span><h2>{{ session.title }}</h2><p>{{ session.queryCount }} 次提问 · {{ dateText(session.updatedAt) }}</p><span class="history-open">查看并继续对话 <Icon name="arrow" :size="17" /></span></RouterLink></article></div><a-pagination v-model:current="page" :page-size="12" :total="data.total" :disabled="deleting" :show-size-changer="false" />
 </template><div v-else class="portal-card"><a-empty description="还没有历史对话，从一个校园问题开始吧" /><RouterLink to="/">去提问</RouterLink></div>
</div></WorkspaceShell></template>
