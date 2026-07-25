# 热点文件站

一台安卓设备充当局域网文件服务器，其他手机、平板或电脑只用浏览器即可上传和下载文件。应用不依赖云服务，也不需要在访问端安装配套 App。

## 已实现

- 使用系统文件夹选择器指定共享范围，不申请整个存储空间权限
- 热点或同一 Wi-Fi 内通过 IP 地址访问
- 手机端显示访问地址、二维码和访问码
- PIN 登录、失败次数限制和仅本次服务有效的登录会话
- 浏览、上传、下载、新建文件夹、重命名和删除
- 多文件上传、拖放上传、进度显示和取消上传
- 大文件流式传输，不把整个文件读入内存
- 下载支持 HTTP Range，可继续未完成的下载
- 同名上传自动生成 `文件名 (1).扩展名`
- 前台服务、Wi-Fi 锁和 CPU 唤醒锁，锁屏后仍可继续传输
- Android 8.0（API 26）及以上

## 构建

推荐使用带 JDK 17 的新版 Android Studio，并安装 Android 16 / API 36 SDK。

1. 在 Android Studio 中打开本目录 `HotspotFileServer`。
2. 等待 Gradle 同步和依赖下载完成。
3. 连接安卓设备，直接运行 `app`。

也可以在 Windows 终端执行：

```powershell
.\gradlew.bat assembleDebug
```

生成的调试 APK 位于：

```text
app\build\outputs\apk\debug\app-debug.apk
```

项目使用 Android Gradle Plugin 9.0.1、Gradle 9.1、JDK 17 和 API 36。

## GitHub 自动构建与发布

仓库内的 `.github/workflows/build-release.yml` 会在推送和手动运行时执行单元测试、lint 和 Release APK 构建。

发布版本时推送形如 `v1.0.0` 的标签，工作流会自动创建 GitHub Release，并上传：

```text
hotspot-file-server.apk
SHA256SUMS.txt
```

Release 构建使用仓库 Secrets 中保存的独立签名密钥，后续版本保持相同签名，可直接覆盖升级。

## 使用

1. 打开应用，选择一个共享文件夹。Android 11 以上不能直接授权整个内部存储根目录或“下载”根目录，可先在其中建立一个子文件夹再选择。
2. 打开手机热点，或让主设备和访问设备连接同一个 Wi-Fi。
3. 设置端口和访问码，点击“启动服务”。
4. 在另一台设备扫描二维码，或在浏览器输入应用显示的地址。
5. 输入访问码后管理共享目录内的文件。

热点由安卓系统管理，普通第三方应用无法在所有品牌设备上静默开启，因此应用提供的是“热点设置”入口，不会自行修改热点配置。

## 安全边界

- 网页只能访问用户通过系统选择器授权的目录及其子目录。
- 服务监听局域网地址，不会上传文件到互联网。
- 当前使用 HTTP。热点/Wi-Fi 密码负责链路加密，PIN 主要用于阻止同一局域网内的误访问。
- 建议只在可信网络中启动，使用完毕后在应用或常驻通知中停止服务。
- 如果设备厂商的省电策略仍会终止服务，可在系统设置中将本应用设为“不受电池优化限制”。

## 项目结构

```text
app/src/main/java/com/lanfileserver/app/
  MainActivity.kt          手机端控制界面
  FileServerService.kt     前台服务与锁屏运行
  LanFileServer.kt         HTTP 路由、登录与流式传输
  StorageTree.kt           共享目录边界和文件操作
  SafePath.kt              路径与文件名校验
  NetworkAddresses.kt      热点/Wi-Fi 地址发现

app/src/main/assets/web/
  login.html               浏览器登录页
  index.html               响应式文件管理器
```
