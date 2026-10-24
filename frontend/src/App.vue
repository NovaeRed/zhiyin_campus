<script setup lang="ts">
import zhCN from 'ant-design-vue/es/locale/zh_CN';
const theme = { token: { colorPrimary: '#2d5a45', borderRadius: 9, fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif' } };
</script>
<template><a-config-provider :locale="zhCN" :theme="theme"><RouterView /></a-config-provider></template>
