<template>
  <ContentPanel>
    <div class="search-form">
      <el-input
        clearable
        placeholder="请输入用户ID或群组ID"
        v-model="contactId"
        size="large"
        @keydown.enter="search"
      ></el-input>
      <div class="search-btn iconfont icon-search" @click="search"></div>
    </div>

    <div v-if="searchResult && Object.keys(searchResult).length > 0" class="search-result-panel">
      <div class="search-result">
        <span class="contact-type">{{ contactTypeName }}</span>
        <UserBaseInfo
          :userInfo="searchResult"
          :showArea="searchResult.contactType == 'USER'"
        ></UserBaseInfo>
      </div>
      <div class="op-btn" v-if="searchResult.contactId != userInfoStore.getInfo().userId">
        <el-button
          type="primary"
          v-if="
            searchResult.status == null ||
            searchResult.status == 0 ||
            searchResult.status == 2 ||
            searchResult.status == 3 ||
            searchResult.status == 4
          "
          @click="applyContact"
          >{{ searchResult.contactType == 'USER' ? '添加到联系人' : '申请加入群组' }}</el-button
        >
        <el-button type="primary" v-if="searchResult.status == 1" @click="sendMessage"
          >发消息</el-button
        >
        <span v-if="searchResult.status == 5 || searchResult.status == 6">对方拉黑了你</span>
      </div>
    </div>
    <div v-if="!searchResult" class="no-data">没有搜索到任何结果</div>
  </ContentPanel>
  <SearchAdd ref="searchAddRef" @reload="resetForm"></SearchAdd>
</template>

<script setup>
import SearchAdd from './SearchAdd.vue'
import { ref, reactive, getCurrentInstance, nextTick, computed, watch } from 'vue'
const { proxy } = getCurrentInstance()
import { useUserInfoStore } from '@/stores/UserInfoStore'
const userInfoStore = useUserInfoStore()

import { useRouter, useRoute } from 'vue-router'
const router = useRouter()
const route = useRoute()

const contactTypeName = computed(() => {
  if (userInfoStore.getInfo().userId === searchResult.value.contactId) {
    return '自己'
  }
  if (searchResult.value.contactType == 'USER') {
    return '用户'
  }
  if (searchResult.value.contactType == 'GROUP') {
    return '群组'
  }
})

const contactId = ref()
const searchResult = ref({})
const search = async () => {
  let result = await proxy.Request({
    url: proxy.Api.search,
    params: {
      contactId: contactId.value
    }
  })
  if (!result) {
    return
  }
  searchResult.value = result.data
}

//添加到通讯录
const searchAddRef = ref()
const applyContact = async () => {
  searchAddRef.value.show(searchResult.value)
}

//从别处带着contactId跳进来（比如联系人页的AI助手列表）就直接搜出来，
//省掉让用户手抄一遍助手ID这一步。
//用watch而不是onMounted：已经停在搜索页时再点另一个助手，组件不会重新挂载
watch(
  () => route.query.contactId,
  (newContactId) => {
    if (newContactId) {
      contactId.value = newContactId
      search()
    }
  },
  { immediate: true }
)

const resetForm = () => {
  searchResult.value = {}
  contactId.value = undefined
}

const sendMessage = () => {
  router.push({ path: '/chat', query: { chatId: searchResult.value.contactId, timestamp: new Date().getTime() } })
}
</script>

<style lang="scss" scoped>
.search-form {
  padding-top: 50px;
  display: flex;
  align-items: center;
  :deep(.el-input__wrapper) {
    border-radius: 4px 0px 0px 4px;
    border-right: none;
  }
  .search-btn {
    background: #07c160;
    color: #fff;
    line-height: 40px;
    width: 80px;
    text-align: center;
    border-radius: 0px 5px 5px 0px;
    cursor: pointer;
    &:hover {
      background: #0dd36c;
    }
  }
}
.no-data {
  padding: 30px 0px;
}
.search-result-panel {
  .search-result {
    padding: 30px 20px 20px 20px;
    background: #fff;
    border-radius: 5px;
    margin-top: 10px;
    position: relative;
    .contact-type {
      position: absolute;
      left: 0px;
      top: 0px;
      background: #2cb6fe;
      padding: 2px 5px;
      color: #fff;
      border-radius: 5px 0px 0px 0px;
      font-size: 12px;
    }
  }
  .op-btn {
    border-radius: 5px;
    margin-top: 10px;
    padding: 10px;
    background: #fff;
    text-align: center;
  }
}
</style>
