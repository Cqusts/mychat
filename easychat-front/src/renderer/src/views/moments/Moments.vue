<template>
  <div class="moments-container">
    <div class="moments-header drag">
      <div class="header-title">朋友圈</div>
      <div class="header-actions no-drag">
        <div class="action-btn iconfont icon-add" @click="showPublish = true"></div>
      </div>
    </div>
    <div class="moments-body" ref="momentsBodyRef" @scroll="handleScroll">
      <!-- 我的头部区域 -->
      <div class="my-moments-header" @click="loadMyMoments">
        <div class="my-info">
          <Avatar :userId="userInfo.userId" :width="50" :showDetail="false"></Avatar>
          <div class="my-name">{{ userInfo.nickName }}</div>
        </div>
      </div>
      <!-- Tab切换 -->
      <div class="tab-panel">
        <div
          :class="['tab-item', currentTab === 'feed' ? 'active' : '']"
          @click="switchTab('feed')"
        >好友动态</div>
        <div
          :class="['tab-item', currentTab === 'mine' ? 'active' : '']"
          @click="switchTab('mine')"
        >我的动态</div>
      </div>
      <!-- 动态列表 -->
      <div class="moments-list">
        <template v-if="momentsList.length > 0">
          <MomentsItem
            v-for="item in momentsList"
            :key="item.momentsId"
            :data="item"
            :currentUserId="userInfo.userId"
            @refresh="refreshList"
          ></MomentsItem>
        </template>
        <div class="empty-tip" v-if="momentsList.length === 0 && !loading">
          {{ currentTab === 'feed' ? '暂无好友动态' : '暂无动态' }}
        </div>
        <div class="loading-tip" v-if="loading">加载中...</div>
        <div class="no-more-tip" v-if="noMore && momentsList.length > 0">没有更多了</div>
      </div>
    </div>
    <MomentsPublish
      :show="showPublish"
      @close="showPublish = false"
      @published="onPublished"
    ></MomentsPublish>
  </div>
</template>

<script setup>
import { ref, reactive, getCurrentInstance, onMounted } from 'vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'
import MomentsItem from './MomentsItem.vue'
import MomentsPublish from './MomentsPublish.vue'

const { proxy } = getCurrentInstance()
const userInfoStore = useUserInfoStore()
const userInfo = userInfoStore.getInfo()

const showPublish = ref(false)
const momentsList = ref([])
const loading = ref(false)
const noMore = ref(false)
const pageNo = ref(1)
const currentTab = ref('feed')
const momentsBodyRef = ref(null)

const loadMomentsFeed = async (page) => {
  if (loading.value) return
  loading.value = true
  try {
    let result = await proxy.Request({
      url: proxy.Api.momentsLoadFeed,
      showLoading: page === 1,
      params: {
        pageNo: page
      }
    })
    if (!result) return
    const data = result.data
    if (page === 1) {
      momentsList.value = data.list || []
    } else {
      momentsList.value = momentsList.value.concat(data.list || [])
    }
    noMore.value = data.pageNo >= data.pageTotal
    pageNo.value = data.pageNo
  } finally {
    loading.value = false
  }
}

const loadMyMomentsList = async (page) => {
  if (loading.value) return
  loading.value = true
  try {
    let result = await proxy.Request({
      url: proxy.Api.momentsLoadUserMoments,
      showLoading: page === 1,
      params: {
        userId: userInfo.userId,
        pageNo: page
      }
    })
    if (!result) return
    const data = result.data
    if (page === 1) {
      momentsList.value = data.list || []
    } else {
      momentsList.value = momentsList.value.concat(data.list || [])
    }
    noMore.value = data.pageNo >= data.pageTotal
    pageNo.value = data.pageNo
  } finally {
    loading.value = false
  }
}

const switchTab = (tab) => {
  if (tab === currentTab.value) return
  currentTab.value = tab
  momentsList.value = []
  noMore.value = false
  pageNo.value = 1
  if (tab === 'feed') {
    loadMomentsFeed(1)
  } else {
    loadMyMomentsList(1)
  }
}

const loadMyMoments = () => {
  switchTab('mine')
}

const refreshList = () => {
  if (currentTab.value === 'feed') {
    loadMomentsFeed(1)
  } else {
    loadMyMomentsList(1)
  }
}

const onPublished = () => {
  switchTab('mine')
  loadMyMomentsList(1)
}

const handleScroll = () => {
  const el = momentsBodyRef.value
  if (!el) return
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 50) {
    if (!loading.value && !noMore.value) {
      const nextPage = pageNo.value + 1
      if (currentTab.value === 'feed') {
        loadMomentsFeed(nextPage)
      } else {
        loadMyMomentsList(nextPage)
      }
    }
  }
}

onMounted(() => {
  loadMomentsFeed(1)
})
</script>

<style lang="scss" scoped>
.moments-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f5f5;
  .moments-header {
    height: 60px;
    background: #f7f7f7;
    display: flex;
    align-items: center;
    padding: 0 15px;
    border-bottom: 1px solid #ddd;
    .header-title {
      flex: 1;
      font-size: 16px;
      font-weight: bold;
      color: #333;
    }
    .header-actions {
      .action-btn {
        font-size: 20px;
        color: #666;
        cursor: pointer;
        padding: 5px;
        border-radius: 4px;
        &:hover {
          background: #e8e8e8;
          color: #07c160;
        }
      }
    }
  }
  .moments-body {
    flex: 1;
    overflow-y: auto;
    &::-webkit-scrollbar {
      width: 6px;
    }
    &::-webkit-scrollbar-thumb {
      background: #bfbfbf;
      border-radius: 10px;
    }
    .my-moments-header {
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      padding: 30px 20px 20px 20px;
      cursor: pointer;
      .my-info {
        display: flex;
        align-items: center;
        .my-name {
          color: #fff;
          font-size: 16px;
          font-weight: bold;
          margin-left: 12px;
          text-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
        }
      }
    }
    .tab-panel {
      display: flex;
      background: #fff;
      border-bottom: 1px solid #eee;
      .tab-item {
        flex: 1;
        text-align: center;
        padding: 12px 0;
        font-size: 14px;
        color: #666;
        cursor: pointer;
        position: relative;
        &:hover {
          color: #333;
        }
      }
      .active {
        color: #07c160;
        font-weight: bold;
        &::after {
          content: '';
          position: absolute;
          bottom: 0;
          left: 50%;
          transform: translateX(-50%);
          width: 30px;
          height: 3px;
          background: #07c160;
          border-radius: 2px;
        }
      }
    }
    .moments-list {
      background: #fff;
      min-height: 200px;
      .empty-tip {
        text-align: center;
        color: #999;
        padding: 60px 0;
        font-size: 14px;
      }
      .loading-tip {
        text-align: center;
        color: #999;
        padding: 20px 0;
        font-size: 13px;
      }
      .no-more-tip {
        text-align: center;
        color: #ccc;
        padding: 15px 0;
        font-size: 12px;
      }
    }
  }
}
</style>
