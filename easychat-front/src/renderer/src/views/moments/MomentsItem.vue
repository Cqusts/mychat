<template>
  <div class="moments-item">
    <div class="item-header">
      <Avatar :userId="data.userId" :width="40" :showDetail="true"></Avatar>
      <div class="header-info">
        <div class="nick-name">{{ data.nickName }}</div>
        <div class="time">{{ formatTime(data.createTime) }}</div>
      </div>
      <div class="delete-btn" v-if="data.userId === currentUserId" @click="doDelete">
        <span class="iconfont icon-close"></span>
      </div>
    </div>
    <div class="item-content" v-if="data.content">
      <span v-html="data.content"></span>
    </div>
    <div class="item-images" v-if="data.images" :class="'img-count-' + imageCount">
      <div class="image-wrapper" v-for="(img, index) in imageArray" :key="index" @click="previewImage(index)">
        <img :src="getImageUrl(img)" />
      </div>
    </div>
    <div class="item-actions">
      <div :class="['action-btn', data.liked ? 'liked' : '']" @click="doLike">
        <span class="iconfont icon-ok"></span>
        <span>{{ data.likeCount > 0 ? data.likeCount : '赞' }}</span>
      </div>
      <div class="action-btn" @click="toggleComment">
        <span class="iconfont icon-chat2"></span>
        <span>{{ data.commentCount > 0 ? data.commentCount : '评论' }}</span>
      </div>
    </div>
    <!-- 点赞列表 -->
    <div class="like-panel" v-if="data.likes && data.likes.length > 0">
      <span class="iconfont icon-ok like-icon"></span>
      <span class="like-users">
        <template v-for="(like, index) in data.likes" :key="like.userId">
          <span class="like-name">{{ like.nickName }}</span>
          <span v-if="index < data.likes.length - 1">，</span>
        </template>
      </span>
    </div>
    <!-- 评论列表 -->
    <div class="comment-panel" v-if="data.comments && data.comments.length > 0">
      <div class="comment-item" v-for="comment in data.comments" :key="comment.commentId">
        <span class="comment-user" @click="replyComment(comment)">{{ comment.nickName }}</span>
        <template v-if="comment.replyUserId">
          <span class="reply-text"> 回复 </span>
          <span class="comment-user">{{ comment.replyNickName }}</span>
        </template>
        <span>：{{ comment.content }}</span>
      </div>
    </div>
    <!-- 评论输入框 -->
    <div class="comment-input" v-if="showCommentInput">
      <el-input
        ref="commentInputRef"
        v-model="commentContent"
        :placeholder="commentPlaceholder"
        size="small"
        @keyup.enter="doComment"
      >
        <template #append>
          <el-button @click="doComment">发送</el-button>
        </template>
      </el-input>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, getCurrentInstance, nextTick } from 'vue'
const { proxy } = getCurrentInstance()

const props = defineProps({
  data: {
    type: Object,
    required: true
  },
  currentUserId: {
    type: String,
    required: true
  }
})

const emit = defineEmits(['refresh'])

const imageArray = computed(() => {
  if (!props.data.images) return []
  return props.data.images.split(',').filter(Boolean)
})

const imageCount = computed(() => {
  return Math.min(imageArray.value.length, 9)
})

const getImageUrl = (imagePath) => {
  const baseURL = (import.meta.env.PROD ? proxy.Api.prodDomain : '') + '/api'
  const token = localStorage.getItem('token')
  return baseURL + proxy.Api.momentsDownloadImage + '?imageId=' + encodeURIComponent(imagePath) + '&token=' + token
}

const formatTime = (timestamp) => {
  if (!timestamp) return ''
  const now = Date.now()
  const diff = now - timestamp
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < minute) return '刚刚'
  if (diff < hour) return Math.floor(diff / minute) + '分钟前'
  if (diff < day) return Math.floor(diff / hour) + '小时前'
  if (diff < 7 * day) return Math.floor(diff / day) + '天前'
  const date = new Date(timestamp)
  return date.getFullYear() + '/' + (date.getMonth() + 1) + '/' + date.getDate()
}

const doLike = async () => {
  let result = await proxy.Request({
    url: proxy.Api.momentsLike,
    showLoading: false,
    params: {
      momentsId: props.data.momentsId
    }
  })
  if (result) {
    emit('refresh')
  }
}

const doDelete = () => {
  proxy.Confirm({
    message: '确定要删除这条动态吗？',
    okfun: async () => {
      let result = await proxy.Request({
        url: proxy.Api.momentsDelete,
        params: {
          momentsId: props.data.momentsId
        }
      })
      if (result) {
        proxy.Message.success('已删除')
        emit('refresh')
      }
    }
  })
}

const showCommentInput = ref(false)
const commentContent = ref('')
const replyUserId = ref(null)
const commentPlaceholder = ref('写评论...')
const commentInputRef = ref(null)

const toggleComment = () => {
  showCommentInput.value = !showCommentInput.value
  replyUserId.value = null
  commentPlaceholder.value = '写评论...'
  if (showCommentInput.value) {
    nextTick(() => {
      commentInputRef.value?.focus()
    })
  }
}

const replyComment = (comment) => {
  showCommentInput.value = true
  replyUserId.value = comment.userId
  commentPlaceholder.value = '回复 ' + comment.nickName
  nextTick(() => {
    commentInputRef.value?.focus()
  })
}

const doComment = async () => {
  if (!commentContent.value.trim()) return
  let result = await proxy.Request({
    url: proxy.Api.momentsComment,
    showLoading: false,
    params: {
      momentsId: props.data.momentsId,
      content: commentContent.value,
      replyUserId: replyUserId.value || ''
    }
  })
  if (result) {
    commentContent.value = ''
    showCommentInput.value = false
    replyUserId.value = null
    emit('refresh')
  }
}

const previewImage = (index) => {
  // 简单预览 - 新窗口打开
  const url = getImageUrl(imageArray.value[index])
  window.open(url)
}
</script>

<style lang="scss" scoped>
.moments-item {
  padding: 15px;
  border-bottom: 1px solid #eee;
  .item-header {
    display: flex;
    align-items: center;
    .header-info {
      flex: 1;
      margin-left: 10px;
      .nick-name {
        font-size: 14px;
        font-weight: bold;
        color: #576b95;
      }
      .time {
        font-size: 12px;
        color: #999;
        margin-top: 2px;
      }
    }
    .delete-btn {
      cursor: pointer;
      color: #999;
      &:hover {
        color: #f56c6c;
      }
    }
  }
  .item-content {
    margin-top: 8px;
    font-size: 14px;
    color: #333;
    line-height: 1.6;
    word-break: break-all;
  }
  .item-images {
    margin-top: 8px;
    display: grid;
    gap: 4px;
    .image-wrapper {
      overflow: hidden;
      border-radius: 4px;
      cursor: pointer;
      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        display: block;
      }
    }
    &.img-count-1 {
      grid-template-columns: 200px;
      .image-wrapper {
        height: 200px;
      }
    }
    &.img-count-2, &.img-count-4 {
      grid-template-columns: repeat(2, 120px);
      .image-wrapper {
        height: 120px;
      }
    }
    &.img-count-3, &.img-count-5, &.img-count-6,
    &.img-count-7, &.img-count-8, &.img-count-9 {
      grid-template-columns: repeat(3, 100px);
      .image-wrapper {
        height: 100px;
      }
    }
  }
  .item-actions {
    margin-top: 8px;
    display: flex;
    gap: 20px;
    .action-btn {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      color: #666;
      cursor: pointer;
      padding: 4px 8px;
      border-radius: 4px;
      &:hover {
        background: #f0f0f0;
      }
      .iconfont {
        font-size: 14px;
      }
    }
    .liked {
      color: #f56c6c;
    }
  }
  .like-panel {
    margin-top: 6px;
    background: #f7f7f7;
    padding: 6px 10px;
    border-radius: 4px;
    font-size: 12px;
    color: #576b95;
    display: flex;
    align-items: flex-start;
    .like-icon {
      font-size: 12px;
      color: #f56c6c;
      margin-right: 4px;
      margin-top: 1px;
    }
    .like-users {
      flex: 1;
      line-height: 1.6;
    }
    .like-name {
      cursor: pointer;
      &:hover {
        text-decoration: underline;
      }
    }
  }
  .comment-panel {
    margin-top: 4px;
    background: #f7f7f7;
    padding: 6px 10px;
    border-radius: 4px;
    font-size: 12px;
    color: #333;
    .comment-item {
      line-height: 1.8;
      .comment-user {
        color: #576b95;
        cursor: pointer;
        &:hover {
          text-decoration: underline;
        }
      }
      .reply-text {
        color: #999;
      }
    }
  }
  .comment-input {
    margin-top: 8px;
  }
}
</style>
