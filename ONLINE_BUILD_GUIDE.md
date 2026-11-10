# 飞记：GitHub 在线编译指南

这是 Kotlin + Jetpack Compose + Room 的原生安卓项目。原 ZIP 中附带的 Vite/React 网页演示已移除。

1. 在 GitHub 创建 **private** 仓库，将本压缩包**解压后的全部内容**上传至仓库根目录（包括 `.github/workflows/build-apk.yml`）。GitHub 网页上传时可能不便上传隐藏目录，建议检查 Actions 是否出现 `Build Android Debug APK` 工作流。
2. 打开 Actions → `Build Android Debug APK` → Run workflow。
3. 等待编译结束，打开运行记录底部的 Artifacts → 下载 `FeiJi-BulletNotes-Debug-APK`。
4. 解压得到 `app-debug.apk`，发送至安卓手机安装。Debug APK 已由构建工具自动签名；不同 GitHub 运行生成的调试签名可能不同，升级前请备份数据。

## 重要限制

- 这里未实际执行 Gradle 编译。工作流会在 GitHub 服务器安装 Gradle 8.9、JDK 17、Android SDK 后尝试编译；如有错误，请提供完整 Actions 日志。
- 原 ZIP 缺少 `gradle-wrapper.jar`。为避免依赖不完整 Wrapper，本项目的云端工作流改为使用 `gradle/actions/setup-gradle` 安装 Gradle 8.9，因此不再包含损坏的 `gradlew` 启动器。
- 每天首次打开时，软件将当前全部笔记以 JSON 保存到应用私有文件目录（原子写入）；Android 10+ 另尽力同步副本到公共 Download 文件夹。**恢复“今天”时使用私有副本**。卸载应用会清除私有副本；公共副本不会自动用于恢复。Android 9 以下公共 Download 写入需要存储权限，当前项目没有运行时请求，故这些系统上只保证私有备份。
- 在 Android 10+，如果用户手动删除公共 Download 副本，系统重新创建时可能出现重名文件；当前实现不保证公共 Download 始终仅有一个同名文件。
- 当前没有备份恢复前的二次安全快照；恢复将覆盖全部笔记，操作前请谨慎确认。
