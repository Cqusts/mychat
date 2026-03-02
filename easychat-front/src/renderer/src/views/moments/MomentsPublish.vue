<template>
  <Dialog
    :show="show"
    title="发布动态"
    width="500px"
    :showCancel="true"
    :buttons="publishButtons"
    @close="close"
  >
    <div class="publish-panel">
      <el-input
        v-model="content"
        type="textarea"
        :rows="4"
        placeholder="说点什么吧..."
        maxlength="1000"
        show-word-limit
      ></el-input>
      <div class="image-upload-panel">
        <div class="image-list">
          <div class="image-item" v-for="(img, index) in imageList" :key="index">
            <img :src="img.url" />
            <div class="delete-btn" @click="removeImage(index)">
              <span class="iconfont icon-close"></span>
            </div>
          </div>
          <div class="image-item add-btn" v-if="imageList.length < 9" @click="selectImage">
            <span class="iconfont icon-add"></span>
          </div>
        </div>
        <input
          ref="fileInputRef"
          type="file"
          accept="image/*"
          multiple
          style="display: none"
          @change="handleFileChange"
        />
      </div>
    </div>
  </Dialog>
</template>

<script setup>
import { ref, getCurrentInstance } from 'vue'
const { proxy } = getCurrentInstance()

const props = defineProps({
  show: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['close', 'published'])

const content = ref('')
const imageList = ref([])
const publishing = ref(false)
const fileInputRef = ref(null)

const publishButtons = ref([
  { type: 'primary', text: '发布', click: () => doPublish() }
])

const close = () => {
  content.value = ''
  imageList.value = []
  emit('close')
}

const selectImage = () => {
  fileInputRef.value.click()
}

const handleFileChange = (e) => {
  const files = e.target.files
  for (let i = 0; i < files.length && imageList.value.length < 9; i++) {
    const file = files[i]
    imageList.value.push({
      file: file,
      url: URL.createObjectURL(file)
    })
  }
  fileInputRef.value.value = ''
}

const removeImage = (index) => {
  URL.revokeObjectURL(imageList.value[index].url)
  imageList.value.splice(index, 1)
}

const doPublish = async () => {
  if (!content.value && imageList.value.length === 0) {
    proxy.Message.warning('请输入内容或选择图片')
    return
  }
  publishing.value = true
  try {
    const formData = new FormData()
    if (content.value) {
      formData.append('content', content.value)
    }
    formData.append('type', imageList.value.length > 0 ? 1 : 0)
    imageList.value.forEach((img) => {
      formData.append('imageFiles', img.file)
    })

    const token = localStorage.getItem('token')
    const baseURL = (import.meta.env.PROD ? proxy.Api.prodDomain : "") + "/api"
    const response = await fetch(baseURL + proxy.Api.momentsPublish, {
      method: 'POST',
      headers: {
        'token': token
      },
      body: formData
    })
    const result = await response.json()
    if (result.code === 200) {
      proxy.Message.success('发布成功')
      close()
      emit('published')
    } else {
      proxy.Message.error(result.info || '发布失败')
    }
  } catch (e) {
    proxy.Message.error('发布失败')
  } finally {
    publishing.value = false
  }
}
</script>

<style lang="scss" scoped>
.publish-panel {
  padding: 10px;
  .image-upload-panel {
    margin-top: 10px;
    .image-list {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      .image-item {
        width: 80px;
        height: 80px;
        border-radius: 4px;
        overflow: hidden;
        position: relative;
        img {
          width: 100%;
          height: 100%;
          object-fit: cover;
        }
        .delete-btn {
          position: absolute;
          top: 2px;
          right: 2px;
          width: 18px;
          height: 18px;
          background: rgba(0, 0, 0, 0.5);
          border-radius: 50%;
          display: flex;
          align-items: center;
          justify-content: center;
          cursor: pointer;
          .iconfont {
            color: #fff;
            font-size: 10px;
          }
        }
      }
      .add-btn {
        border: 1px dashed #ccc;
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        background: #fafafa;
        &:hover {
          border-color: #07c160;
        }
        .iconfont {
          font-size: 24px;
          color: #999;
        }
      }
    }
  }
}
</style>
