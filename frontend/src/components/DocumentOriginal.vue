<script setup lang="ts">
import { ref, watch } from 'vue';
import { getDocumentOriginal } from '../services/portal';
import type { DocumentRecord } from '../services/portal';
import { errorMessage } from '../services/api';
const props = defineProps<{ document: DocumentRecord }>();
const source = ref<{ url: string; text?: string; filename: string }>(),
  loading = ref(true),
  error = ref(''),
  revision = ref(0);
watch(
  [() => props.document.id, () => props.document.title, () => props.document.fileType, revision],
  async (_values, _old, onCleanup) => {
    let active = true,
      url: string | undefined;
    onCleanup(() => {
      active = false;
      if (url) URL.revokeObjectURL(url);
    });
    loading.value = true;
    error.value = '';
    source.value = undefined;
    try {
      const response = await getDocumentOriginal(props.document.id);
      const text = ['txt', 'md', 'html'].includes(props.document.fileType.toLowerCase())
        ? await response.data.text()
        : undefined;
      if (!active) return;
      url = URL.createObjectURL(response.data);
      source.value = { url, text, filename: `${props.document.title}.${props.document.fileType}` };
    } catch (e) {
      if (active) error.value = errorMessage(e);
    } finally {
      if (active) loading.value = false;
    }
  },
  { immediate: true },
);
</script>
<template>
  <div
    v-if="loading"
    class="original-loading"
  >
    <a-spin tip="正在读取原始文档…" />
  </div>
  <a-alert
    v-else-if="error"
    type="error"
    show-icon
    message="原始文件加载失败"
    :description="error"
    ><template #action><a-button @click="revision++">重试</a-button></template></a-alert
  >
  <section
    v-else-if="source"
    class="document-original"
    aria-label="原始文档预览"
  >
    <a-space
      wrap
      class="original-toolbar"
      ><a-button
        :href="source.url"
        :download="source.filename"
        >下载原始文件</a-button
      ><span class="muted-text"
        >{{ document.fileType.toUpperCase() }} · 上传时保留的原始文件</span
      ></a-space
    >
    <template v-if="document.fileType.toLowerCase() === 'pdf'"
      ><p class="form-helper">若浏览器无法显示 PDF，可下载后查看。</p>
      <iframe
        title="原始PDF文档"
        class="original-frame"
        :src="source.url"
    /></template>
    <iframe
      v-else-if="document.fileType.toLowerCase() === 'html'"
      title="原始HTML文档"
      class="original-frame"
      sandbox=""
      referrerpolicy="no-referrer"
      :srcdoc="`<!doctype html><meta charset='utf-8'><meta http-equiv='Content-Security-Policy' content=&quot;default-src 'none'; style-src 'unsafe-inline'; img-src data:&quot;>${source.text || ''}`"
    />
    <pre
      v-else-if="source.text !== undefined"
      class="original-text"
      >{{ source.text }}</pre>
    <a-alert
      v-else
      type="info"
      show-icon
      message="此格式支持下载查看"
      description="请下载原始文件，使用本地文档或表格应用打开。"
    />
  </section>
</template>
