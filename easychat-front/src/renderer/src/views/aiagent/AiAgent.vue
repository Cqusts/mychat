<template>
  <div class="ai-agent-page">
    <div class="page-header drag">
      <div class="title">AI 助手</div>
      <div class="sub-title">
        每个助手都是一个真实的联系人，加为好友就能私聊；也可以把它们拉进群，@昵称就会发言
      </div>
    </div>

    <div class="agent-list" v-loading="loading">
      <div class="agent-card" v-for="agent in agentList" :key="agent.contactId">
        <div class="card-top">
          <AvatarBase
            :userId="agent.contactId"
            :width="48"
            :borderRadius="8"
            :showDetail="false"
          ></AvatarBase>
          <div class="name-panel">
            <div class="name">
              {{ agent.contactName }}
              <span class="added-tag" v-if="agent.inContact">已添加</span>
            </div>
            <div class="signature">{{ agent.signature }}</div>
          </div>
        </div>

        <div class="ability-list">
          <div class="ability-item" v-for="(ability, index) in abilitiesOf(agent)" :key="index">
            <span class="dot"></span>{{ ability }}
          </div>
        </div>

        <div class="card-op">
          <el-button type="primary" size="default" @click="startChat(agent)" :loading="agent.adding">
            {{ agent.inContact ? '发消息' : '添加并聊天' }}
          </el-button>
        </div>
      </div>

      <div class="no-data" v-if="!loading && agentList.length == 0">
        后台还没有配置任何 AI 助手，检查 application.properties 里的 ai.agents 配置
      </div>
    </div>
  </div>
</template>

<script setup>
import AvatarBase from '@/components/AvatarBase.vue'
import { ref, getCurrentInstance } from 'vue'
import { useRouter } from 'vue-router'
const { proxy } = getCurrentInstance()
const router = useRouter()

import { useContactStateStore } from '@/stores/ContactStateStore'
const contactStateStore = useContactStateStore()

const agentList = ref([])
const loading = ref(true)

//能力说明后端用 | 分隔，这里拆成列表展示
const abilitiesOf = (agent) => {
  if (!agent.description) {
    return []
  }
  return agent.description.split('|').filter((item) => item.trim())
}

const loadAgents = async () => {
  loading.value = true
  let result = await proxy.Request({
    url: proxy.Api.loadAiAgents
  })
  loading.value = false
  if (!result) {
    return
  }
  agentList.value = result.data.map((item) => {
    return { ...item, adding: false }
  })
}

//没加过就先加为好友再进聊天，加过就直接进聊天。
//助手的joinType是"直接加入"，applyAdd会立刻建立好友关系，不需要等审批
const startChat = async (agent) => {
  if (!agent.inContact) {
    agent.adding = true
    let result = await proxy.Request({
      url: proxy.Api.applyAdd,
      params: {
        contactId: agent.contactId,
        contactType: 'USER',
        applyInfo: `你好，我想找${agent.contactName}帮忙`
      }
    })
    agent.adding = false
    if (!result) {
      return
    }
    agent.inContact = true
    //通知联系人页刷新，否则通讯录里看不到刚加的助手
    contactStateStore.setContactReload('USER')
    proxy.Message.success(`已添加${agent.contactName}`)
  }
  //必须带timestamp：Chat页是keep-alive的，它watch的是timestamp而不是chatId，
  //不带的话从助手页反复点不同助手不会切换会话
  router.push({ path: '/chat', query: { chatId: agent.contactId, timestamp: new Date().getTime() } })
}

loadAgents()
</script>

<style lang="scss" scoped>
.ai-agent-page {
  height: calc(100vh - 2px);
  display: flex;
  flex-direction: column;
  background: #f5f5f5;

  .page-header {
    padding: 18px 25px 14px 25px;
    background: #fff;
    border-bottom: 1px solid #ddd;
    .title {
      font-size: 17px;
      font-weight: bold;
      color: #000;
    }
    .sub-title {
      margin-top: 5px;
      font-size: 12px;
      color: #8a8a8a;
    }
  }

  .agent-list {
    flex: 1;
    overflow-y: auto;
    padding: 20px 25px;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
    gap: 16px;
    align-content: start;
  }

  .agent-card {
    display: flex;
    flex-direction: column;
    padding: 16px;
    background: #fff;
    border: 1px solid #e6e6e6;
    border-radius: 8px;
    transition: box-shadow 0.2s;
    &:hover {
      box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
    }

    .card-top {
      display: flex;
      align-items: center;
      .name-panel {
        margin-left: 12px;
        overflow: hidden;
        .name {
          display: flex;
          align-items: center;
          font-size: 15px;
          font-weight: bold;
          color: #000;
          .added-tag {
            margin-left: 8px;
            padding: 1px 5px;
            font-size: 11px;
            font-weight: normal;
            color: #07c160;
            border: 1px solid #07c160;
            border-radius: 3px;
          }
        }
        .signature {
          margin-top: 4px;
          font-size: 12px;
          color: #8a8a8a;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
      }
    }

    .ability-list {
      flex: 1;
      margin: 14px 0;
      .ability-item {
        display: flex;
        align-items: flex-start;
        margin-bottom: 7px;
        font-size: 13px;
        line-height: 18px;
        color: #474747;
        .dot {
          flex-shrink: 0;
          width: 5px;
          height: 5px;
          margin: 6px 8px 0 2px;
          border-radius: 50%;
          background: #07c160;
        }
      }
    }

    .card-op {
      :deep(.el-button) {
        width: 100%;
      }
    }
  }

  .no-data {
    grid-column: 1 / -1;
    padding: 40px 0;
    text-align: center;
    font-size: 13px;
    color: #8a8a8a;
  }
}
</style>
