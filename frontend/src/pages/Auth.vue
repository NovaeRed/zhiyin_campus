<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import Icon from '../components/Icon.vue';
import authService from '../services/auth';
import { useAuth } from '../composables/auth';
import { errorMessage } from '../services/api';
import '../styles/Auth.css';
const props = withDefaults(defineProps<{ register?: boolean }>(), { register: false });
const model = reactive({ student_no: '', username: '', password: '', confirm: '' }),
  loading = ref(false);
const router = useRouter(),
  { reload } = useAuth();
const strength = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,}$/;
watch(
  () => props.register,
  () => {
    model.password = '';
    model.confirm = '';
  },
);
async function submit() {
  if (loading.value) return;
  loading.value = true;
  try {
    if (props.register) {
      await authService.register(model.student_no.trim(), model.username.trim(), model.password);
      message.success('注册成功，请登录');
      await router.push('/login');
    } else {
      const result = await authService.login(model.student_no.trim(), model.password);
      if (!result.access_token) throw new Error('登录响应不完整');
      await reload();
      await router.push('/');
    }
  } catch (e) {
    message.error(errorMessage(e));
  } finally {
    loading.value = false;
  }
}
function confirmPassword(_rule: unknown, value: string) {
  return !value || value === model.password
    ? Promise.resolve()
    : Promise.reject(new Error('两次输入的密码不一致'));
}
</script>
<template>
  <main class="auth-page">
    <section class="auth-art">
      <RouterLink
        class="brand"
        to="/login"
        ><span class="brand-mark"
          ><Icon
            name="book"
            :size="25" /></span
        ><span>知引校园<small>ZHIYIN CAMPUS</small></span></RouterLink
      >
      <div class="auth-intro">
        <span>在校园里，遇见更清晰的答案</span>
        <h1>每一次好奇，<br />都有知识回应。</h1>
        <p>课程、学分、奖助政策。<br />你的校园探索，从一个问题开始。</p>
      </div>
      <img
        src="/campus-garden.png"
        alt="温暖阳光下的校园图书馆"
      />
      <div class="auth-art-footer">YOUR CAMPUS COMPANION <span>探索 · 求知 · 成长</span></div>
    </section>
    <section class="auth-form-area">
      <div class="auth-form">
        <span class="auth-kicker">WELCOME TO ZHIYIN</span>
        <h2>{{ register ? '开启你的校园探索' : '欢迎回来，同学' }}</h2>
        <p class="auth-subtitle">
          {{
            register
              ? '创建学生账号。管理员及编辑权限由系统负责人分配。'
              : '登录知引，让校园里的疑问有处可问。'
          }}
        </p>
        <a-form
          :key="String(register)"
          :model="model"
          layout="vertical"
          :required-mark="false"
          size="large"
          @finish="submit"
        >
          <a-form-item
            name="student_no"
            label="学号"
            :rules="[{ required: true, whitespace: true, message: '请输入学号' }]"
            ><a-input
              v-model:value="model.student_no"
              placeholder="请输入你的学号"
              autocomplete="username"
          /></a-form-item>
          <a-form-item
            v-if="register"
            name="username"
            label="用户名"
            :rules="[{ required: true, whitespace: true, message: '请输入用户名' }]"
            ><a-input
              v-model:value="model.username"
              placeholder="你希望我们如何称呼你"
              autocomplete="nickname"
          /></a-form-item>
          <a-form-item
            name="password"
            label="密码"
            :rules="[
              { required: true, message: '请输入密码' },
              ...(register
                ? [
                    {
                      pattern: strength,
                      message: '至少8位，包含大小写字母、数字和 @$!%*?& 中的符号',
                    },
                  ]
                : []),
            ]"
            ><a-input-password
              v-model:value="model.password"
              placeholder="请输入密码"
              :autocomplete="register ? 'new-password' : 'current-password'"
          /></a-form-item>
          <a-form-item
            v-if="register"
            name="confirm"
            label="确认密码"
            :dependencies="['password']"
            :rules="[{ required: true, message: '请再次输入密码' }, { validator: confirmPassword }]"
            ><a-input-password
              v-model:value="model.confirm"
              placeholder="再次输入密码"
              autocomplete="new-password"
          /></a-form-item>
          <a-button
            type="primary"
            html-type="submit"
            :loading="loading"
            block
            class="auth-submit"
            >{{ register ? '创建账号' : '登录校园'
            }}<Icon
              name="arrow"
              :size="18"
          /></a-button>
        </a-form>
        <p class="auth-switch">
          {{ register ? '已经有账号？' : '初次来到知引？'
          }}<RouterLink :to="register ? '/login' : '/register'">{{
            register ? '返回登录' : '创建账号'
          }}</RouterLink>
        </p>
        <div class="auth-trust">
          <Icon
            name="file"
            :size="15"
          />基于校园资料 · 回答附有参考来源
        </div>
      </div>
      <footer>知引校园 · 让知识有迹可循</footer>
    </section>
  </main>
</template>
