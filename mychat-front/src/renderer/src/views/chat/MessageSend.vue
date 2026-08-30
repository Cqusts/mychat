<template>
  <div class="send-panel">
    <!--@提及成员面板：挂在send-panel下而不是input-area里，
        因为input-area有overflow:auto，浮到框外的元素会被裁掉-->
    <div class="mention-panel" v-if="mentionVisible && mentionList.length > 0">
      <div
        :class="['mention-item', index == mentionIndex ? 'active' : '']"
        v-for="(item, index) in mentionList"
        :key="item.userId"
        @mousedown.prevent="pickMention(item)"
        @mouseenter="mentionIndex = index"
      >
        <Avatar :userId="item.userId" :width="24" :showDetail="false"></Avatar>
        <div class="nick-name">{{ item.contactName }}</div>
        <div class="agent-tag" v-if="agentIdSet.has(item.userId)">AI</div>
      </div>
    </div>
    <div class="toolbar">
      <el-popover
        :visible="showEmojiPopover"
        trigger="click"
        placement="top"
        :teleported="false"
        @show="openPopover"
        @hide="closePopover"
        :popper-style="{
          padding: '0px 10px 10px 10px',
          width: '490px'
        }"
      >
        <template #default>
          <el-tabs v-model="activeEmoji" @click.stop>
            <el-tab-pane :label="emoji.name" :name="emoji.name" v-for="emoji in emojiList">
              <div class="emoji-list">
                <div class="emoji-item" v-for="item in emoji.emojiList" @click="sendEmoji(item)">
                  {{ item }}
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </template>
        <template #reference>
          <div class="iconfont icon-emoji" @click="showEmojiPopoverHandler"></div>
        </template>
      </el-popover>
      <el-upload
        ref="uploadRef"
        name="file"
        :show-file-list="false"
        :multiple="true"
        :limit="fileLimit"
        :http-request="uploadFile"
        :on-exceed="uploadExceed"
      >
        <div class="iconfont icon-folder"></div>
      </el-upload>
    </div>
    <div class="input-area" @drop="dropHandler" @dragover="dragOverHandler">
      <el-input
        ref="msgInputRef"
        :rows="5"
        v-model="msgContent"
        type="textarea"
        resize="none"
        maxlength="500"
        show-word-limit
        spellcheck="false"
        input-style="background:#f5f5f5;border:none;"
        @keydown.enter="onEnterKey"
        @keydown.tab="onTabKey"
        @keydown.up="onArrowKey($event, -1)"
        @keydown.down="onArrowKey($event, 1)"
        @keydown.esc="closeMention"
        @input="detectMention"
        @click="detectMention"
        @blur="closeMention"
        @paste="pasteFile"
      />
    </div>
    <div class="send-btn-panel">
      <el-popover
        trigger="click"
        :visible="showSendMsgPopover"
        :hide-after="1500"
        placement="top-end"
        :teleported="false"
        @show="openPopover"
        @hide="closePopover"
        :popper-style="{
          padding: '5px',
          'min-width': '0px',
          width: '120px'
        }"
      >
        <template #default><span class="empty-msg">不能发送空白信息</span></template>
        <template #reference>
          <span class="send-btn" @click="sendMessage">发送(S)</span>
        </template>
      </el-popover>
    </div>
    <!--添加好友-->
    <SearchAdd ref="searchAddRef"></SearchAdd>
  </div>
</template>

<script setup>
import SearchAdd from '@/views/contact/SearchAdd.vue'
import {getFileType} from '@/utils/Constants.js'
import {computed, getCurrentInstance, nextTick, onMounted, onUnmounted, ref} from 'vue'
import emojiList from '@/utils/Emoji.js'
import {useUserInfoStore} from '@/stores/UserInfoStore'
import {useSysSettingStore} from '@/stores/SysSettingStore'

const {proxy} = getCurrentInstance()
const userInfoStore = useUserInfoStore()

const sysSettingStore = useSysSettingStore()

const props = defineProps({
  currentChatSession: {
    type: Object,
    default: {}
  }
})

//======================= @提及成员 =======================
//这个项目原本没有@功能，只能手打昵称。群里要@助手才能让它发言，所以补上。
const msgInputRef = ref()
const mentionVisible = ref(false)
const mentionIndex = ref(0)
const mentionQuery = ref('')
const memberList = ref([])
const agentIdSet = ref(new Set())
//记录成员列表是哪个群的，换群要重新拉
const loadedGroupId = ref(null)

//当前群聊里可@的人：排除自己，按输入的关键词过滤
const mentionList = computed(() => {
  const keyword = mentionQuery.value.toLowerCase()
  const myUserId = userInfoStore.getInfo().userId
  return memberList.value
    .filter((item) => item.userId != myUserId)
    .filter((item) => {
      if (!keyword) {
        return true
      }
      return (item.contactName || '').toLowerCase().includes(keyword)
    })
    .slice(0, 8)
})

const isGroupChat = () => {
  return props.currentChatSession && props.currentChatSession.contactType == 1
}

const loadMentionData = async () => {
  const groupId = props.currentChatSession.contactId
  if (!groupId || loadedGroupId.value == groupId) {
    return
  }
  let result = await proxy.Request({
    url: proxy.Api.getGroupInfo4Chat,
    params: { groupId },
    showError: false
  })
  if (!result) {
    return
  }
  loadedGroupId.value = groupId
  memberList.value = result.data.userContactList || []
  //顺便标出哪些成员是AI助手，@助手是群里最主要的用法
  if (agentIdSet.value.size == 0) {
    let agentResult = await proxy.Request({
      url: proxy.Api.loadAiAgents,
      showError: false
    })
    if (agentResult) {
      agentIdSet.value = new Set(agentResult.data.map((item) => item.contactId))
    }
  }
}

//光标位置往前找最近的@，判断当前是不是正在输入一个提及
const detectMention = () => {
  if (!isGroupChat()) {
    closeMention()
    return
  }
  const textarea = msgInputRef.value ? msgInputRef.value.textarea : null
  //直接读DOM里的值和光标位置：不用去纠结el-input的input和update:modelValue谁先触发
  const text = textarea ? textarea.value : msgContent.value
  const caret = textarea ? textarea.selectionStart : text.length
  const before = text.slice(0, caret)
  const atIndex = before.lastIndexOf('@')
  if (atIndex < 0) {
    closeMention()
    return
  }
  //@前面必须是行首或空白，避免把邮箱之类的也当成提及
  const prevChar = atIndex > 0 ? before.charAt(atIndex - 1) : ''
  if (prevChar && !/\s/.test(prevChar)) {
    closeMention()
    return
  }
  const keyword = before.slice(atIndex + 1)
  //关键词里出现空白说明这次@已经输完了
  if (/\s/.test(keyword)) {
    closeMention()
    return
  }
  mentionQuery.value = keyword
  mentionIndex.value = 0
  //面板从关闭变为打开时强制刷新一次：
  //刚在群详情里把助手拉进群、回到聊天窗就@，用缓存会漏掉它
  if (!mentionVisible.value) {
    loadedGroupId.value = null
  }
  mentionVisible.value = true
  loadMentionData()
}

const closeMention = () => {
  mentionVisible.value = false
  mentionQuery.value = ''
}

//把光标处的“@关键词”替换成“@昵称 ”
const pickMention = (member) => {
  if (!member) {
    return
  }
  const textarea = msgInputRef.value ? msgInputRef.value.textarea : null
  const text = textarea ? textarea.value : msgContent.value
  const caret = textarea ? textarea.selectionStart : text.length
  const before = text.slice(0, caret)
  const atIndex = before.lastIndexOf('@')
  if (atIndex < 0) {
    closeMention()
    return
  }
  const inserted = '@' + member.contactName + ' '
  msgContent.value = before.slice(0, atIndex) + inserted + text.slice(caret)
  closeMention()
  //等DOM更新完再把光标放到插入内容之后
  nextTick(() => {
    if (!textarea) {
      return
    }
    const newCaret = atIndex + inserted.length
    textarea.focus()
    textarea.setSelectionRange(newCaret, newCaret)
  })
}

const onArrowKey = (e, step) => {
  if (!mentionVisible.value || mentionList.value.length == 0) {
    return
  }
  //面板开着的时候上下键用来选人，不去移动光标
  e.preventDefault()
  const total = mentionList.value.length
  mentionIndex.value = (mentionIndex.value + step + total) % total
}

const onTabKey = (e) => {
  if (!mentionVisible.value || mentionList.value.length == 0) {
    return
  }
  e.preventDefault()
  pickMention(mentionList.value[mentionIndex.value])
}

//面板开着时回车是“选中这个人”，不是发消息
const onEnterKey = (e) => {
  if (mentionVisible.value && mentionList.value.length > 0) {
    e.preventDefault()
    pickMention(mentionList.value[mentionIndex.value])
    return
  }
  sendMessage(e)
}

const cleanMessage = () => {
  msgContent.value = ''
  closeMention()
}
defineExpose({
  cleanMessage
})

const activeEmoji = ref('笑脸')

//发送消息
const msgContent = ref('')

const emit = defineEmits(['sendMessage4Local'])
const sendMessage = async (e) => {
  //shift +enter 换行  enter 发送
  if (e.shiftKey && e.keyCode === 13) {
    return
  }
  e.preventDefault()
  const messageContent = msgContent.value ? msgContent.value.replace(/\s*$/g, '') : ''
  if (messageContent == '') {
    showSendMsgPopover.value = true
    return
  }
  sendMessageDo({messageContent, messageType: 2}, true)
}

//添加好友
const searchAddRef = ref()
const addContact = (contactId, code) => {
  searchAddRef.value.show({
    contactId,
    contactType: code == 902 ? 'USER' : 'GROUP'
  })
}

//发送消息
const sendMessageDo = async (
  messageObj = {
    messageContent,
    messageType,
    localFilePath,
    fileSize,
    fileName,
    filePath,
    fileType
  },
  cleanMsgContent
) => {
  if (!checkFileSize(messageObj.fileType, messageObj.fileSize, messageObj.fileName)) {
    return
  }

  if (messageObj.fileSize == 0) {
    proxy.Confirm({
      message: `"${messageObj.fileName}"是一个空文件无法发送，请重新选择`,
      showCancelBtn: false
    })
    return
  }
  messageObj.sessionId = props.currentChatSession.sessionId
  messageObj.sendUserId = userInfoStore.getInfo().userId

  //请求服务器发送消息
  let result = await proxy.Request({
    url: proxy.Api.sendMessage,
    showLoading: false,
    params: {
      messageContent: messageObj.messageContent,
      contactId: props.currentChatSession.contactId,
      messageType: messageObj.messageType,
      fileSize: messageObj.fileSize,
      fileName: messageObj.fileName,
      fileType: messageObj.fileType
    },
    showError: false,
    errorCallback: (responseData) => {
      proxy.Confirm({
        message: responseData.info,
        okfun: () => {
          addContact(props.currentChatSession.contactId, responseData.code)
        },
        okText: '重新申请'
      })
    }
  })
  if (!result) {
    return
  }
  //更新本地消息
  if (cleanMsgContent) {
    msgContent.value = ''
  }
  Object.assign(messageObj, result.data)
  //更新列表
  emit('sendMessage4Local', messageObj)
  //保存消息到本地
  window.ipcRenderer.send('addLocalMessage', messageObj)
}

//表情相关
const sendEmoji = (emoji) => {
  msgContent.value = msgContent.value + emoji
  showEmojiPopover.value = false
}

const showEmojiPopoverHandler = () => {
  showEmojiPopover.value = true
}

const showSendMsgPopover = ref(false)
const showEmojiPopover = ref(false)

const hidePopover = () => {
  showSendMsgPopover.value = false
  showEmojiPopover.value = false
}
const openPopover = () => {
  document.addEventListener('click', hidePopover, false)
}
const closePopover = () => {
  document.removeEventListener('click', hidePopover, false)
}

//校验文件大小
const checkFileSize = (fileType, fileSize, fileName) => {
  const SIZE_MB = 1024 * 1024
  const settingArray = Object.values(sysSettingStore.getSetting())
  //图片
  if (fileSize > settingArray[fileType] * SIZE_MB) {
    proxy.Confirm({
      message: `文件${fileName}超过大小${settingArray[fileType]}MB限制`,
      showCancelBtn: false
    })
    return false
  }
  return true
}

//发送文件
const fileLimit = 10
const checkFileLimit = (files) => {
  if (files.length > fileLimit) {
    proxy.Confirm({
      message: `一次最多可以上传10个文件`,
      showCancelBtn: false
    })
    return
  }
  return true
}
//拖入文件
const dragOverHandler = () => {
  event.preventDefault()
}
const dropHandler = (event) => {
  event.preventDefault()
  const files = event.dataTransfer.files
  if (!checkFileLimit(files)) {
    return
  }
  for (let i = 0; i < files.length; i++) {
    uploadFileDo(files[i])
  }
}

//文件个数超过指定值
const uploadExceed = (files) => {
  checkFileLimit(files)
}
//上传文件
const uploadRef = ref()
const uploadFile = (file) => {
  uploadFileDo(file.file)
  uploadRef.value.clearFiles()
}

const getFileTypeByName = (fileName) => {
  const fileSuffix = fileName.substr(fileName.lastIndexOf('.') + 1)
  return getFileType(fileSuffix)
}

const uploadFileDo = (file) => {
  const fileType = getFileTypeByName(file.name)
  sendMessageDo(
    {
      messageContent: '[' + getFileType(fileType) + ']',
      messageType: 5,
      fileSize: file.size,
      fileName: file.name,
      filePath: file.path,
      fileType: fileType
    },
    false
  )
}

//截图粘贴上传文件
const pasteFile = async (event) => {
  let items = event.clipboardData && event.clipboardData.items
  const fileData = {}
  for (const item of items) {
    if (item.kind != 'file') {
      break
    }
    const file = await item.getAsFile()
    if (file.path != '') {
      uploadFileDo(file)
    } else {
      const imageFile = new File([file], 'temp.jpg')
      let fileReader = new FileReader()
      fileReader.onloadend = function () {
        // 读取完成后获得结果
        const byteArray = new Uint8Array(this.result)
        fileData.byteArray = byteArray
        fileData.name = imageFile.name
        window.ipcRenderer.send('saveClipBoardFile', fileData)
      }
      fileReader.readAsArrayBuffer(imageFile)
    }
  }
}

onMounted(() => {
  window.ipcRenderer.on('saveClipBoardFileCallback', (e, file) => {
    const fileType = 0
    sendMessageDo(
      {
        messageContent: '[' + getFileType(fileType) + ']',
        messageType: 5,
        fileSize: file.size,
        fileName: file.name,
        filePath: file.path,
        fileType: fileType
      },
      false
    )
  })
})
onUnmounted(() => {
  window.ipcRenderer.removeAllListeners('saveClipBoardFileCallback')
})
</script>

<style lang="scss" scoped>
.mention-panel {
  position: absolute;
  left: 10px;
  bottom: 100%;
  z-index: 10;
  width: 200px;
  max-height: 240px;
  overflow-y: auto;
  margin-bottom: 4px;
  padding: 4px 0;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.12);

  .mention-item {
    display: flex;
    align-items: center;
    padding: 5px 10px;
    cursor: pointer;

    .nick-name {
      flex: 1;
      margin-left: 8px;
      font-size: 13px;
      color: #333;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .agent-tag {
      flex-shrink: 0;
      padding: 0 4px;
      font-size: 11px;
      color: #fff;
      background: #576b95;
      border-radius: 3px;
    }

    &.active {
      background: #ededed;
    }
  }
}

.emoji-list {
  .emoji-item {
    float: left;
    font-size: 23px;
    padding: 2px;
    text-align: center;
    border-radius: 3px;
    margin-left: 10px;
    margin-top: 5px;
    cursor: pointer;

    &:hover {
      background: #ddd;
    }
  }
}

.send-panel {
  height: 200px;
  border-top: 1px solid #ddd;
  //@提及面板的定位基准
  position: relative;

  .toolbar {
    height: 40px;
    display: flex;
    align-items: center;
    padding-left: 10px;

    .iconfont {
      color: #494949;
      font-size: 20px;
      margin-left: 10px;
      cursor: pointer;
    }

    :deep(.el-tabs__header) {
      margin-bottom: 0px;
    }
  }

  .input-area {
    padding: 0px 10px;
    outline: none;
    width: 100%;
    height: 115px;
    overflow: auto;
    word-wrap: break-word;
    word-break: break-all;

    :deep(.el-textarea__inner) {
      box-shadow: none;
    }

    :deep(.el-input__count) {
      background: none;
      right: 12px;
    }
  }

  .send-btn-panel {
    text-align: right;
    padding-top: 10px;
    margin-right: 22px;

    .send-btn {
      cursor: pointer;
      color: #07c160;
      background: #e9e9e9;
      border-radius: 5px;
      padding: 8px 25px;

      &:hover {
        background: #d2d2d2;
      }
    }

    .empty-msg {
      font-size: 13px;
    }
  }
}
</style>
