<template>
  <section class="call-trend" aria-label="当前记录的调用趋势">
    <div class="trend-heading"><h2>调用趋势</h2><span>{{ days[0] || '暂无记录' }}{{ days.length > 1 ? ' 至 ' + days[days.length - 1] : '' }} · UTC 日 · 单位：次</span></div>
    <div v-if="days.length" ref="chartElement" class="trend-chart" role="img" :aria-label="description" />
    <el-empty v-else description="当前筛选结果暂无调用记录" :image-size="48" />
    <p>仅统计当前页返回的记录；查询或翻页后更新。逻辑调用与执行尝试分别计数，费用以明细为准。</p>
  </section>
</template>
<script setup lang="ts">
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import useSettingsStore from '@/store/modules/settings'
echarts.use([BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])
const props = defineProps<{ records: { createdAt: string; factKind?: string }[] }>()
const settings = useSettingsStore(), chartElement = ref<HTMLElement>()
const buckets = computed(() => {
  const result: Record<string, { logical: number; attempts: number }> = {}
  for (const record of props.records) {
    const date = new Date(record.createdAt)
    if (Number.isNaN(date.getTime())) continue
    const day = date.toISOString().slice(0, 10)
    result[day] ||= { logical: 0, attempts: 0 }
    result[day][record.factKind === 'ATTEMPT' ? 'attempts' : 'logical']++
  }
  return result
})
const days = computed(() => Object.keys(buckets.value).sort())
const description = computed(() => days.value.map((day: string) => day + '：逻辑调用 ' + buckets.value[day].logical + ' 次，执行尝试 ' + buckets.value[day].attempts + ' 次').join('；'))
let chart: echarts.ECharts | undefined, observer: ResizeObserver | undefined
function render() {
  if (!chartElement.value) { chart?.dispose(); chart = undefined; return }
  chart ||= echarts.init(chartElement.value)
  chart.setOption({ animation: false, color: ['#0071e3', settings.isDark ? '#a6b4c7' : '#8e9db0'], tooltip: { trigger: 'axis' }, legend: { top: 0, textStyle: { color: settings.isDark ? '#a1a7b1' : '#70747c' } }, grid: { left: 34, right: 10, top: 40, bottom: 24 }, xAxis: { type: 'category', data: days.value, axisLabel: { color: settings.isDark ? '#a1a7b1' : '#70747c' }, axisLine: { show: false }, axisTick: { show: false } }, yAxis: { type: 'value', minInterval: 1, axisLabel: { color: settings.isDark ? '#a1a7b1' : '#70747c' }, splitLine: { lineStyle: { color: settings.isDark ? '#333740' : '#eceef1' } } }, series: [{ name: '逻辑业务调用', type: 'bar', barMaxWidth: 24, data: days.value.map((day: string) => buckets.value[day].logical) }, { name: '执行尝试', type: 'bar', barMaxWidth: 24, data: days.value.map((day: string) => buckets.value[day].attempts) }] }, true)
  chart.resize()
}
watch([() => props.records, () => settings.isDark, chartElement], () => nextTick(render), { deep: true })
onMounted(() => { render(); observer = new ResizeObserver(() => chart?.resize()); if (chartElement.value) observer.observe(chartElement.value) })
watch(chartElement, (element: HTMLElement | undefined) => { observer?.disconnect(); if (element) observer?.observe(element) })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<style scoped>
.call-trend { margin: 22px 0 26px; }.trend-heading { display: flex; align-items: baseline; gap: 18px; flex-wrap: wrap; }.trend-heading h2 { font-size: 14px; font-weight: 600; margin: 0; }.trend-heading span, .call-trend p { font-size: 14px; color: var(--ln-muted); line-height: 1.7; }.trend-chart { height: 210px; width: 100%; margin-top: 16px; }
</style>
