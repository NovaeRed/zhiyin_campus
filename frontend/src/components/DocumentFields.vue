<script setup lang="ts">
import type { DocumentValues } from '../types/documents';
defineProps<{ upload?: boolean }>();
const model = defineModel<DocumentValues>({ required: true });
</script>
<template>
 <a-form-item name="title" label="文档标题" :rules="[{ required: true, whitespace: true, message: '请输入文档标题' }, { max: 255 }]"><a-input v-model:value="model.title" :maxlength="255" placeholder="如：2024级计算机科学与技术培养方案" /></a-form-item>
 <a-form-item name="version" label="文档版本" :rules="[{ required: true, whitespace: true }, { max: 32 }]"><a-input v-model:value="model.version" :maxlength="32" placeholder="v1" /></a-form-item>
 <a-form-item v-if="upload" name="strategy" label="切片策略"><a-select v-model:value="model.strategy" allow-clear placeholder="使用后端默认策略" :options="[{ value: 'recursive', label: '递归切片' }, { value: 'parent_child', label: '父子切片' }]" /></a-form-item>
 <div class="form-grid"><a-form-item name="targetMajor" label="适用专业"><a-input v-model:value="model.targetMajor" :maxlength="100" :placeholder="upload ? '留空可按正文推断' : '通用资料可留空'" /></a-form-item><a-form-item name="targetGrade" label="入学年份"><a-input-number v-model:value="model.targetGrade" :min="1900" :max="2200" :precision="0" placeholder="如2024" style="width:100%" /></a-form-item></div>
 <div class="form-grid"><a-form-item name="targetCohort" label="毕业届别" :rules="[{ pattern: /^20\d{2}届$/, message: '请输入如2028届，或留空' }]"><a-input v-model:value="model.targetCohort" :maxlength="32" placeholder="如2028届，非入学年份" /></a-form-item><a-form-item name="department" label="发布部门"><a-input v-model:value="model.department" :maxlength="100" placeholder="如教务处" /></a-form-item></div>
 <a-form-item name="effectiveDate" label="生效日期"><a-input v-model:value="model.effectiveDate" type="date" /></a-form-item>
</template>
