<template>
  <n-config-provider :theme="isDark ? darkTheme : null" :theme-overrides="themeOverrides">
    <n-message-provider>
      <main>
        <TranslateWorkbench />
      </main>
    </n-message-provider>
  </n-config-provider>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { NConfigProvider, NMessageProvider, darkTheme } from 'naive-ui'
import TranslateWorkbench from '@/components/TranslateWorkbench.vue'

const media = window.matchMedia('(prefers-color-scheme: dark)')
const isDark = ref(media.matches)
const syncTheme = (event) => { isDark.value = event.matches }
media.addEventListener('change', syncTheme)
onBeforeUnmount(() => media.removeEventListener('change', syncTheme))

const themeOverrides = computed(() => {
  const accent = isDark.value ? '#efa396' : '#a83b32'
  const hover = isDark.value ? '#ffc1b7' : '#8d3029'
  return {
    common: {
      primaryColor: accent,
      primaryColorHover: hover,
      primaryColorPressed: hover,
      primaryColorSuppl: accent
    }
  }
})
</script>
