<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const activeMenu = computed(() => route.path)
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">无线测试</div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/">Dashboard</el-menu-item>
        <el-menu-item index="/agents">Agents</el-menu-item>
        <el-menu-item index="/devices">设备管理</el-menu-item>
        <el-menu-item index="/files">文件传输</el-menu-item>
        <el-menu-item index="/tests">测试编排</el-menu-item>
        <el-menu-item index="/events">事件流</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">无线自动化测试控制台</span>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}
.aside {
  border-right: 1px solid var(--el-border-color);
  display: flex;
  flex-direction: column;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  border-bottom: 1px solid var(--el-border-color);
}
.menu {
  border-right: none;
  flex: 1;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--el-border-color);
}
.title {
  font-size: 16px;
  font-weight: 600;
}
.main {
  background: var(--el-fill-color-lighter);
}
</style>
