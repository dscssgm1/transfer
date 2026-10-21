import { createApp } from 'vue'
import App from './App.vue'

// 自托管字体：只引入实际用到的字重和 latin 子集，
// 避免在 CSS 里 @import Google Fonts（国内会超时并阻塞首屏渲染）。
import '@fontsource/manrope/latin-400.css'
import '@fontsource/manrope/latin-500.css'
import '@fontsource/manrope/latin-600.css'
import '@fontsource/manrope/latin-700.css'
import '@fontsource/manrope/latin-800.css'
import '@fontsource/dm-mono/latin-400.css'
import '@fontsource/dm-mono/latin-500.css'

import './styles.css'

createApp(App).mount('#app')
