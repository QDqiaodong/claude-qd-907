<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import router from '../router'
import api from '../api'

const board = ref({ orders: [], methods: [], stages: [] })
const loading = ref(false)
const submitting = ref(false)
const stageFilter = ref('ALL')
const keyword = ref('')
const selectedOrderId = ref(null)
const detail = ref(null)

const completeForm = ref({ responsiblePerson: '', dispositionMethod: '', dispositionNote: '', completedBy: '' })
const inspectForm = ref({ inspector: '钱广安', hardnessHb: null, remark: '', defectMap: {} })
const reinspectDialog = ref(false)
const correctionDialog = ref(false)
const correctionRoundId = ref(null)
const correctionForm = ref({ correctionNote: '', operator: '' })

const filteredQueue = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return (board.value.orders || []).filter((item) => {
    const matchStage = stageFilter.value === 'ALL' || item.stage === stageFilter.value
    const text = `${item.order?.reworkNo || ''} ${item.order?.castingNo || ''} ${item.casting?.castingName || ''}`.toLowerCase()
    return matchStage && (!key || text.includes(key))
  })
})

const latestRound = computed(() => detail.value?.timeline
  ?.filter((x) => x.type === 'REWORK')
  .map((x) => x.round)
  .at(-1) || null)
const currentSnapshots = computed(() => {
  if (!['PENDING', 'WAIT_REINSPECT'].includes(latestRound.value?.stage)) return []
  return detail.value.timeline?.find((x) => x.type === 'REWORK' && x.round?.id === latestRound.value.id)?.snapshots || []
})
const canComplete = computed(() => latestRound.value?.stage === 'PENDING')
const canWithdraw = computed(() => latestRound.value?.stage === 'WAIT_REINSPECT')
const canReinspect = computed(() => latestRound.value?.stage === 'WAIT_REINSPECT')
const canCorrect = computed(() => !!latestRound.value?.reinspectionId)

async function load() {
  loading.value = true
  try {
    board.value = await api.rework.board()
    const selectedExists = board.value.orders?.some((item) => item.order.id === selectedOrderId.value)
    if (selectedOrderId.value && selectedExists) {
      await loadDetail(selectedOrderId.value)
    } else if (board.value.orders?.length) {
      await loadDetail(board.value.orders[0].order.id)
    } else {
      selectedOrderId.value = null
      detail.value = null
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function loadDetail(id) {
  try {
    detail.value = await api.rework.detail(id)
    selectedOrderId.value = id
    const round = latestRound.value
    if (round) {
      completeForm.value = {
        responsiblePerson: round.responsiblePerson || '',
        dispositionMethod: round.dispositionMethod || (board.value.methods?.[0]?.code || ''),
        dispositionNote: round.dispositionNote || '',
        completedBy: round.completedBy || ''
      }
    }
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitComplete() {
  if (!latestRound.value) return
  if (!completeForm.value.responsiblePerson.trim()) return ElMessage.warning('请登记责任人')
  if (!completeForm.value.dispositionMethod) return ElMessage.warning('请选择处置方式')
  if (!completeForm.value.dispositionNote.trim()) return ElMessage.warning('请填写处置说明')
  await submitAction(() => api.rework.complete(latestRound.value.id, {
    ...completeForm.value,
    completedBy: completeForm.value.completedBy || completeForm.value.responsiblePerson
  }), '返修完成结果已登记')
}

async function withdrawComplete() {
  if (!latestRound.value) return
  try {
    const { value } = await ElMessageBox.prompt('复检单尚未创建，撤回后本轮回到待返修。请输入撤回操作者。', '撤回本轮完成结果', {
      confirmButtonText: '确认撤回',
      cancelButtonText: '取消',
      inputValue: completeForm.value.completedBy || ''
    })
    await submitAction(() => api.rework.withdraw(latestRound.value.id, { operator: value }), '已撤回，本轮重新进入待返修')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || String(e))
  }
}

function openReinspect() {
  inspectForm.value = {
    inspector: '钱广安',
    hardnessHb: null,
    remark: '',
    defectMap: Object.fromEntries(currentSnapshots.value.map((s) => [s.id, false]))
  }
  reinspectDialog.value = true
}

const selectedDefects = computed(() => currentSnapshots.value
  .filter((s) => inspectForm.value.defectMap[s.id])
  .map((s) => ({
    defectType: s.defectType,
    severity: s.severity,
    position: s.position,
    sizeMm: s.sizeMm,
    quantity: s.quantity,
    remark: s.remark,
    sourceSnapshotId: s.id
  })))

async function submitReinspection(verdict) {
  if (verdict !== 'PASS' && selectedDefects.value.length === 0) {
    return ElMessage.warning('再次返修或报废必须至少勾选一条上一轮缺陷')
  }
  if (verdict === 'PASS' && selectedDefects.value.length > 0) {
    return ElMessage.warning('复检合格不能同时登记缺陷')
  }
  await submitAction(() => api.rework.reinspect(latestRound.value.id, {
    inspector: inspectForm.value.inspector,
    hardnessHb: inspectForm.value.hardnessHb,
    verdict,
    remark: inspectForm.value.remark,
    defects: selectedDefects.value
  }), verdict === 'PASS' ? '复检合格，铸件已入库' : verdict === 'SCRAP' ? '已复检报废，闭环终止' : '已生成下一轮返修待办')
  reinspectDialog.value = false
}

function openCorrection(roundId = latestRound.value?.id) {
  correctionRoundId.value = roundId
  correctionForm.value = { correctionNote: '', operator: '' }
  correctionDialog.value = true
}

async function submitCorrection() {
  if (!correctionForm.value.correctionNote.trim()) return ElMessage.warning('更正说明不能为空')
  await submitAction(() => api.rework.correction(correctionRoundId.value, correctionForm.value), '更正说明已追加，原记录未改写')
  correctionDialog.value = false
}

async function submitAction(action, success) {
  submitting.value = true
  try {
    await action()
    ElMessage.success(success)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
    await load()
  } finally {
    submitting.value = false
  }
}

function tagType(stage) {
  return { PENDING: 'warning', WAIT_REINSPECT: 'primary', PASS: 'success', SCRAPPED: 'danger', REINSPECTED: 'info' }[stage] || ''
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

onMounted(async () => {
  await load()
  const queryId = Number(router.currentRoute.value?.query?.orderId || 0)
  if (queryId) await loadDetail(queryId)
  else if (board.value.orders?.length) await loadDetail(board.value.orders[0].order.id)
})
</script>

<template>
  <div v-loading="loading">
    <div class="view-head">
      <span class="view-title">返修处置与复检闭环 · 独立返修队列</span>
      <span class="view-tip">首次缺陷快照不可改；未复检前可撤回，复检创建后只能追加更正；合格才入库，报废永久终止</span>
    </div>

    <div class="summary-row">
      <div class="summary-card warn"><b>{{ board.pendingCount || 0 }}</b><span>待返修积压</span></div>
      <div class="summary-card primary"><b>{{ board.waitReinspectCount || 0 }}</b><span>待复检</span></div>
      <div class="summary-card"><b>{{ board.activeCount || 0 }}</b><span>闭环中</span></div>
    </div>

    <div class="work-grid">
      <section class="panel">
        <div class="panel-title">返修队列</div>
        <div class="filter-row">
          <el-radio-group v-model="stageFilter" size="small">
            <el-radio-button v-for="s in board.stages" :key="s.code" :value="s.code">{{ s.name }}</el-radio-button>
          </el-radio-group>
          <el-input v-model="keyword" size="small" placeholder="单号 / 铸件" clearable style="width:190px" />
        </div>

        <el-table :data="filteredQueue" size="small" border stripe highlight-current-row @row-click="(row) => loadDetail(row.order.id)">
          <el-table-column label="阶段" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType(row.stage)">{{ row.order.stageName || row.stage }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="order.reworkNo" label="返修单号" width="165" />
          <el-table-column prop="order.castingNo" label="铸件编号" width="132" />
          <el-table-column prop="casting.castingName" label="名称" min-width="150" />
          <el-table-column label="轮次" width="70">
            <template #default="{ row }">第{{ row.roundNo }}轮</template>
          </el-table-column>
          <el-table-column prop="responsiblePerson" label="责任人" width="92" />
          <el-table-column prop="dispositionMethodName" label="处置方式" width="100" />
          <el-table-column label="完成/操作者" width="150">
            <template #default="{ row }">
              <div>{{ formatTime(row.completedAt) }}</div>
              <small>{{ row.completedBy || '—' }}</small>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="panel detail-panel" v-if="detail">
        <div class="panel-title">
          {{ detail.order.reworkNo }}
          <el-tag size="small" :type="tagType(latestRound?.stage || detail.order.currentStage)" style="margin-left:8px">
            {{ detail.order.statusName }}
          </el-tag>
        </div>
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="铸件编号">{{ detail.order.castingNo }}</el-descriptions-item>
          <el-descriptions-item label="铸件名称">{{ detail.casting?.castingName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="当前状态">{{ detail.casting?.statusName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="当前轮次">第 {{ detail.order.currentRoundNo }} 轮</el-descriptions-item>
        </el-descriptions>

        <div v-if="canComplete" class="action-box">
          <div class="box-title">登记本轮返修完成</div>
          <el-form label-width="82px" size="small">
            <el-form-item label="责任人">
              <el-input v-model="completeForm.responsiblePerson" placeholder="实际返修责任人" />
            </el-form-item>
            <el-form-item label="处置方式">
              <el-select v-model="completeForm.dispositionMethod" style="width:100%">
                <el-option v-for="m in board.methods" :key="m.code" :label="m.name" :value="m.code" />
              </el-select>
            </el-form-item>
            <el-form-item label="完成操作者">
              <el-input v-model="completeForm.completedBy" placeholder="默认同责任人" />
            </el-form-item>
            <el-form-item label="处置说明">
              <el-input v-model="completeForm.dispositionNote" type="textarea" :rows="3" />
            </el-form-item>
          </el-form>
          <el-button type="primary" :loading="submitting" @click="submitComplete">提交返修完成</el-button>
        </div>

        <div v-else-if="canWithdraw || canReinspect" class="action-box">
          <div class="box-title">等待复检</div>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="责任人">{{ latestRound.responsiblePerson }}</el-descriptions-item>
            <el-descriptions-item label="处置方式">{{ latestRound.dispositionMethodName }}</el-descriptions-item>
            <el-descriptions-item label="完成时间">{{ formatTime(latestRound.completedAt) }}</el-descriptions-item>
            <el-descriptions-item label="登记操作者">{{ latestRound.completedBy }}</el-descriptions-item>
            <el-descriptions-item label="处置说明">{{ latestRound.dispositionNote }}</el-descriptions-item>
          </el-descriptions>
          <div class="button-row">
            <el-button type="primary" :loading="submitting" :disabled="!canReinspect" @click="openReinspect">发起复检</el-button>
            <el-button type="warning" plain :loading="submitting" :disabled="!canWithdraw" @click="withdrawComplete">撤回完成</el-button>
          </div>
        </div>

        <div v-else-if="canCorrect" class="action-box closed">
          <div class="box-title">闭环已有复检结论</div>
          <p>原始返修处置与复检证据均不可改写；如发现误填，只能追加更正说明。</p>
          <el-button size="small" @click="openCorrection()">追加更正说明</el-button>
        </div>

        <div v-else class="empty-tip">请选择队列中的返修单</div>
      </section>
    </div>

    <section class="panel timeline-panel" v-if="detail">
      <div class="panel-title">证据时间线 · 首次缺陷 / 每轮处置 / 复检结论</div>
      <div class="timeline">
        <div v-for="(event, idx) in detail.timeline" :key="idx" class="time-item" :class="event.type.toLowerCase()">
          <div class="time-dot"></div>
          <div class="time-card">
            <div class="time-head">
              <b>{{ event.title }}</b>
              <span>{{ formatTime(event.time) }}</span>
            </div>

            <template v-if="event.type === 'INITIAL' || event.type === 'REINSPECTION'">
              <el-descriptions :column="4" size="small">
                <el-descriptions-item label="检验单">{{ event.inspection.inspectNo }}</el-descriptions-item>
                <el-descriptions-item label="类型">{{ event.inspection.inspectionKindName }}</el-descriptions-item>
                <el-descriptions-item label="结论">
                  <el-tag size="small" :type="event.inspection.verdict === 'PASS' ? 'success' : event.inspection.verdict === 'SCRAP' ? 'danger' : 'warning'">
                    {{ event.inspection.verdictName }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="检验员">{{ event.inspection.inspector }}</el-descriptions-item>
              </el-descriptions>
            </template>

            <template v-else>
              <div class="round-head">
                <el-descriptions :column="4" size="small">
                <el-descriptions-item label="责任人">{{ event.round.responsiblePerson || '—' }}</el-descriptions-item>
                <el-descriptions-item label="方式">{{ event.round.dispositionMethodName || '—' }}</el-descriptions-item>
                <el-descriptions-item label="操作者">{{ event.round.completedBy || '—' }}</el-descriptions-item>
                <el-descriptions-item label="撤回">{{ event.round.withdrawnBy ? event.round.withdrawnBy + ' · ' + formatTime(event.round.withdrawnAt) : '—' }}</el-descriptions-item>
              </el-descriptions>
                <el-button
                  v-if="event.round.reinspectionId"
                  size="small"
                  plain
                  class="correction-btn"
                  @click="openCorrection(event.round.id)"
                >追加更正</el-button>
              </div>
              <div v-if="event.round.dispositionNote" class="note">处置说明：{{ event.round.dispositionNote }}</div>
            </template>

            <el-table :data="event.snapshots || event.defects || []" size="small" border style="margin-top:8px">
              <el-table-column prop="defectTypeName" label="缺陷" width="80" />
              <el-table-column prop="severityName" label="严重度" width="80" />
              <el-table-column prop="position" label="部位" width="110" />
              <el-table-column prop="sizeMm" label="尺寸 mm" width="82" />
              <el-table-column prop="quantity" label="数量" width="60" />
              <el-table-column prop="remark" label="说明" min-width="180" />
            </el-table>

            <div v-for="c in event.corrections || []" :key="c.id" class="correction">
              更正 {{ formatTime(c.createdAt) }} · {{ c.operator }}：{{ c.correctionNote }}
            </div>
          </div>
        </div>
      </div>
    </section>

    <el-dialog v-model="reinspectDialog" title="基于上一轮缺陷发起复检" width="820px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px"
        title="只能引用当前返修轮保留的缺陷快照；合格无需选缺陷，再次返修/报废至少勾选一条。" />
      <el-form label-width="82px" size="small">
        <el-form-item label="检验员"><el-input v-model="inspectForm.inspector" /></el-form-item>
        <el-form-item label="硬度 HB"><el-input-number v-model="inspectForm.hardnessHb" :min="0" :step="1" controls-position="right" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="inspectForm.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <el-table :data="currentSnapshots" size="small" border @selection-change="() => {}">
        <el-table-column width="48">
          <template #default="{ row }">
            <el-checkbox v-model="inspectForm.defectMap[row.id]" />
          </template>
        </el-table-column>
        <el-table-column prop="defectTypeName" label="快照缺陷" width="90" />
        <el-table-column prop="severityName" label="严重度" width="80" />
        <el-table-column prop="position" label="部位" width="110" />
        <el-table-column prop="sizeMm" label="尺寸" width="70" />
        <el-table-column prop="quantity" label="数量" width="60" />
        <el-table-column prop="remark" label="首次/上轮说明" min-width="180" />
      </el-table>
      <template #footer>
        <el-button @click="reinspectDialog = false">取消</el-button>
        <el-button type="warning" :loading="submitting" @click="submitReinspection('REWORK')">再次返修</el-button>
        <el-button type="danger" :loading="submitting" @click="submitReinspection('SCRAP')">报废</el-button>
        <el-button type="success" :loading="submitting" @click="submitReinspection('PASS')">合格入库</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="correctionDialog" title="追加返修更正说明" width="520px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom:10px"
        title="复检已创建，原责任人、方式、完成时间和处置说明不会被覆盖。" />
      <el-form label-width="82px">
        <el-form-item label="操作者"><el-input v-model="correctionForm.operator" /></el-form-item>
        <el-form-item label="更正说明"><el-input v-model="correctionForm.correctionNote" type="textarea" :rows="4" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="correctionDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCorrection">追加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.summary-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 12px; }
.summary-card { padding: 14px 16px; border-radius: 12px; background: #fff; border: 1px solid var(--line-strong); }
.summary-card b { display: block; font-size: 26px; color: var(--primary-deep); }
.summary-card span { font-size: 12px; color: var(--ink-dim); }
.summary-card.warn b { color: #fb8c00; }
.summary-card.primary b { color: #8e24aa; }
.work-grid { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(0, 1fr); gap: 12px; align-items: start; }
.filter-row { display: flex; justify-content: space-between; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
.detail-panel { min-height: 360px; }
.action-box { margin-top: 12px; padding: 12px; border-radius: 10px; background: #fbf8fd; border: 1px dashed var(--primary-soft); }
.box-title { font-weight: 800; color: var(--primary-deep); margin-bottom: 8px; }
.button-row { margin-top: 10px; display: flex; gap: 8px; }
.closed p { font-size: 12px; color: var(--ink-dim); line-height: 1.7; margin: 6px 0 10px; }
.timeline-panel { margin-top: 12px; }
.timeline { padding: 4px 0 0 4px; }
.time-item { position: relative; padding-left: 22px; margin-bottom: 12px; }
.time-dot { position: absolute; left: 0; top: 6px; width: 10px; height: 10px; border-radius: 50%; background: var(--primary); box-shadow: 0 0 0 3px var(--primary-wash); }
.time-item.reinspection .time-dot { background: #8e24aa; box-shadow: 0 0 0 3px #f3e5f5; }
.time-card { padding-bottom: 8px; border-bottom: 1px dashed var(--line-strong); }
.time-head { display: flex; justify-content: space-between; gap: 10px; margin-bottom: 6px; font-size: 13px; }
.time-head span { color: var(--ink-dim); font-size: 12px; }
.note, .correction { margin-top: 7px; font-size: 12px; color: var(--ink-soft); }
.round-head { position: relative; }
.correction-btn { position: absolute; right: 0; top: -2px; }
.correction { padding: 6px 8px; border-radius: 7px; background: #fff7e6; color: #ad5f00; }
</style>
