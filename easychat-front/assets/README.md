# assets 目录

## 需要自己下载的两个文件

```
easychat-front/assets/
├── 404.png        ✅ 仓库里有
├── user.png       ✅ 仓库里有
├── ffmpeg.exe     ❌ 需要自己下载
└── ffprobe.exe    ❌ 需要自己下载
```

`ffmpeg.exe` 和 `ffprobe.exe` 是几十 MB 的二进制文件，不适合放进版本库，
所以仓库里没有，也被 `.gitignore` 排除了。**没有这两个文件，下面这些功能会直接报错**：

- 上传头像 / 群头像（要生成缩略图）
- 发送视频消息（要探测编码格式、生成封面、必要时转码）

发文字、发图片、AI 功能都不受影响，可以先跳过这一步。

## 下载方式

### Windows

1. 到 <https://www.gyan.dev/ffmpeg/builds/> 下载 **ffmpeg-release-essentials.zip**
   （国内网络慢的话用 <https://github.com/BtbN/FFmpeg-Builds/releases> 的 `ffmpeg-master-latest-win64-gpl.zip`）
2. 解压，在 `bin/` 目录里找到 `ffmpeg.exe` 和 `ffprobe.exe`
3. 把这两个文件复制到本目录（`easychat-front/assets/`）

放好之后目录里应该有 4 个文件，`ffmpeg.exe` 大概 70–150 MB。

### macOS / Linux

代码里写死的是 `.exe` 后缀（见 `src/main/file.js` 顶部的 `ffmpegPath` / `ffprobePath`），
非 Windows 平台需要改成对应的可执行文件名，并把 `brew install ffmpeg` /
`apt install ffmpeg` 装好的二进制软链到这里，或者直接改成绝对路径。

## 怎么确认放对了

启动客户端，随便传一张图当头像。放对了会正常显示裁剪后的头像；
没放对会弹出「缺少 ffmpeg 组件：…」的提示，提示里带完整路径，照着放即可。
