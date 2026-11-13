<script setup>
import { computed, nextTick, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { createDatasetClient, trimName } from './client';
import './datasets.css';

const props = defineProps({
  client: { type: Object, required: true },
  writable: { type: Boolean, default: true },
});
const api = createDatasetClient(props.client);
const route = useRoute();
const router = useRouter();
const rows = ref([]);
const cursors = ref([undefined]);
const page = ref(0);
const nextCursor = ref(null);
const loading = ref(false);
const detailLoading = ref(false);
const error = ref('');
const detailError = ref('');
const selected = ref(null);
const editing = ref(false);
const creating = ref(false);
const saving = ref(false);
const name = ref('');
const description = ref('');
const formError = ref('');
const fields = ref({});
const notice = ref('');
const nameInput = ref(null);
const errorSummary = ref(null);
const createButton = ref(null);
const editButton = ref(null);
const selectedId = computed(() => route.path.slice('/datasets/'.length));
let listSequence = 0;
let detailSequence = 0;

async function loadPage(index = page.value) {
  const sequence = ++listSequence;
  loading.value = true;
  error.value = '';
  try {
    const result = await api.list(cursors.value[index]);
    if (sequence !== listSequence) return;
    rows.value = result.items;
    nextCursor.value = result.nextCursor;
    page.value = index;
  } catch (failure) {
    if (sequence === listSequence) error.value = failure.message;
  } finally {
    if (sequence === listSequence) loading.value = false;
  }
}
async function nextPage() {
  cursors.value[page.value + 1] = nextCursor.value;
  await loadPage(page.value + 1);
}
async function loadDetail() {
  const sequence = ++detailSequence;
  selected.value = null;
  editing.value = false;
  creating.value = false;
  detailError.value = '';
  detailLoading.value = false;
  if (route.path === '/datasets') return;
  detailLoading.value = true;
  try {
    const result = await api.get(selectedId.value);
    if (sequence === detailSequence) selected.value = result;
  } catch (failure) {
    if (sequence === detailSequence) detailError.value = failure.message;
  } finally {
    if (sequence === detailSequence) detailLoading.value = false;
  }
}
async function openForm(create) {
  creating.value = create;
  editing.value = !create;
  name.value = create ? '' : selected.value.name;
  description.value = create ? '' : selected.value.description;
  fields.value = {};
  formError.value = '';
  notice.value = '';
  await nextTick();
  nameInput.value?.focus();
}
async function closeForm() {
  const create = creating.value;
  creating.value = false;
  editing.value = false;
  await nextTick();
  (create ? createButton.value : editButton.value)?.focus();
}
async function submit() {
  if (saving.value) return;
  fields.value = {};
  if (!trimName(name.value)) fields.value.name = '名称不能为空。';
  else if (Array.from(name.value).length > 120)
    fields.value.name = '名称不能超过 120 字。';
  if (Array.from(description.value).length > 2000)
    fields.value.description = '说明不能超过 2000 字。';
  if (Object.keys(fields.value).length) {
    formError.value = '请检查以下字段后重新提交。';
    await nextTick();
    errorSummary.value?.focus();
    return;
  }
  const create = creating.value;
  const original = selected.value;
  const sequence = detailSequence;
  const data = create
    ? {
        name: name.value,
        ...(description.value ? { description: description.value } : {}),
      }
    : {
        ...(name.value === original.name ? {} : { name: name.value }),
        ...(description.value === original.description
          ? {}
          : { description: description.value }),
      };
  if (!Object.keys(data).length) {
    await closeForm();
    return;
  }
  saving.value = true;
  formError.value = '';
  try {
    const result = create
      ? await api.create(data)
      : await api.update(original.id, data);
    if (sequence !== detailSequence) return;
    selected.value = result;
    await closeForm();
    notice.value = create ? '数据集已创建。' : '数据集已保存。';
    if (create) await router.push(`/datasets/${result.id}`);
    cursors.value = [undefined];
    await loadPage(0);
  } catch (failure) {
    if (sequence !== detailSequence) return;
    formError.value = failure.message;
    for (const field of failure.fieldErrors || []) {
      const key = field.field.replace(/^\//, '');
      if (['name', 'description'].includes(key))
        fields.value[key] = field.message;
    }
    await nextTick();
    errorSummary.value?.focus();
  } finally {
    saving.value = false;
  }
}
watch(() => route.path, loadDetail, { immediate: true });
loadPage(0);
</script>

<template>
  <section class="datasets-page" aria-labelledby="datasets-title">
    <header class="datasets-heading">
      <div>
        <p class="welcome-kicker">数据接入</p>
        <h1 id="datasets-title">数据集</h1>
        <p>集中管理原始资料，按主题组织你的知识来源。</p>
      </div>
      <button
        ref="createButton"
        class="primary-button"
        :disabled="!writable || saving"
        @click="openForm(true)"
      >
        创建数据集
      </button>
    </header>
    <p v-if="!writable" role="status">当前写入已暂停，仍可查看已有数据集。</p>
    <p v-if="notice" role="status" class="dataset-notice">{{ notice }}</p>
    <div class="datasets-layout">
      <section
        class="dataset-list"
        aria-label="数据集列表"
        :aria-busy="loading"
      >
        <div class="dataset-list-heading">
          <h2>全部数据集</h2>
          <button class="text-button" :disabled="loading" @click="loadPage()">
            刷新列表
          </button>
        </div>
        <p v-if="error" role="alert">
          {{ error }}
          <button class="text-button" @click="loadPage()">重试列表</button>
        </p>
        <p v-if="loading" role="status">正在加载数据集…</p>
        <p v-else-if="!rows.length && !error" class="dataset-empty">
          尚无数据集。点击“创建数据集”开始整理资料。
        </p>
        <ul v-if="rows.length" class="dataset-rows">
          <li v-for="item in rows" :key="item.id">
            <RouterLink
              :to="`/datasets/${item.id}`"
              :aria-current="selected?.id === item.id ? 'page' : undefined"
              ><strong>{{ item.name }}</strong
              ><span>{{ item.description || '暂无说明' }}</span
              ><small
                >{{ item.fileCount }} 份资料 · {{ item.totalBytes }} 字节</small
              ></RouterLink
            >
          </li>
        </ul>
        <div class="dataset-pagination">
          <button :disabled="loading || page === 0" @click="loadPage(page - 1)">
            上一页</button
          ><span>第 {{ page + 1 }} 页</span
          ><button :disabled="loading || nextCursor === null" @click="nextPage">
            下一页
          </button>
        </div>
      </section>
      <section
        class="dataset-detail"
        aria-label="数据集详情"
        :aria-busy="detailLoading"
      >
        <form
          v-if="creating || editing"
          class="dataset-form"
          novalidate
          @submit.prevent="submit"
          @keydown.esc="!saving && closeForm()"
        >
          <h2>{{ creating ? '创建数据集' : '修改数据集' }}</h2>
          <div
            v-if="formError"
            ref="errorSummary"
            role="alert"
            tabindex="-1"
            class="dataset-error"
          >
            <p>{{ formError }}</p>
            <a
              v-for="(message, field) in fields"
              :key="field"
              :href="`#dataset-${field}`"
              >{{ message }}</a
            >
          </div>
          <label for="dataset-name">名称 <span>必填，最多 120 字</span></label
          ><input
            id="dataset-name"
            ref="nameInput"
            v-model="name"
            :disabled="saving"
            :aria-invalid="!!fields.name"
            aria-describedby="dataset-name-error"
            autocomplete="off"
          />
          <p id="dataset-name-error" class="field-error">{{ fields.name }}</p>
          <label for="dataset-description"
            >说明 <span>选填，最多 2000 字</span></label
          ><textarea
            id="dataset-description"
            v-model="description"
            :disabled="saving"
            :aria-invalid="!!fields.description"
            aria-describedby="dataset-description-error"
            rows="6"
          ></textarea>
          <p id="dataset-description-error" class="field-error">
            {{ fields.description }}
          </p>
          <p class="dataset-help">
            名称去首尾空白后，同类不区分大小写唯一。清空说明后保存即可移除说明。
          </p>
          <div class="dataset-form-actions">
            <button class="primary-button" :disabled="saving || !writable">
              {{ saving ? '正在保存…' : '保存数据集' }}</button
            ><button type="button" :disabled="saving" @click="closeForm">
              取消
            </button>
          </div>
        </form>
        <template v-else>
          <p v-if="detailLoading" role="status">正在读取数据集…</p>
          <div v-else-if="detailError" role="alert">
            {{ detailError }}
            <button class="text-button" @click="loadDetail">重试详情</button>
          </div>
          <template v-else-if="selected"
            ><div class="dataset-detail-heading">
              <h2>{{ selected.name }}</h2>
              <button
                ref="editButton"
                :disabled="!writable"
                @click="openForm(false)"
              >
                修改名称与说明
              </button>
            </div>
            <p class="dataset-description">
              {{ selected.description || '暂无说明' }}
            </p>
            <dl>
              <div>
                <dt>资料数量</dt>
                <dd>{{ selected.fileCount }} 份</dd>
              </div>
              <div>
                <dt>资料大小</dt>
                <dd>{{ selected.totalBytes }} 字节</dd>
              </div>
              <div>
                <dt>创建时间</dt>
                <dd>{{ selected.createdAt }}</dd>
              </div>
              <div>
                <dt>更新时间</dt>
                <dd>{{ selected.updatedAt }}</dd>
              </div>
            </dl>
            <div v-if="selected.fileCount === 0" class="dataset-empty">
              <h3>还没有资料</h3>
              <p>数据集已就绪，文件导入将在后续功能中接通。</p>
            </div></template
          >
          <p v-else class="dataset-empty">
            从左侧选择一个数据集，查看说明与资料概况。
          </p>
        </template>
      </section>
    </div>
  </section>
</template>
