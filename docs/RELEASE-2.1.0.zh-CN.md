# 独立发行版 2.1.0

发行整理日期：2026-10-17。源码基线：`9bc75f0b7263ebd7da5a69be05b717c536abeb3b`。

## 安装包与身份

- [APK](../dist/blocker-2.1.0-foss-release.apk)：FOSS Release，启用 R8，非 Debug。
- 版本：`2.1.0`，versionCode：`6443`；包名：`com.stc214.blocker`。
- [SHA256](../dist/SHA256SUMS.txt)、[签名验证](../dist/signature.txt)、[公开证书](../dist/release-certificate.der)。
- 这是独立安装，不覆盖原版或之前的 Debug 包。安装后向新包授予 root，按需导入设置。
  应用私有配置不自动迁移；系统 IFW 规则仍是共享的，不需要为了迁移清空目录。
- 原项目商店链接和发行徽章仍指向上游，不代表本分支 APK。

## 本次修改

1. 所有 IFW 文件写入/删除通过统一 root 命令执行：临时取消目录不可变属性，原子提交 XML，最后恢复不可变。
   无论初始目录可变还是不可变，结束后都设为不可变。
2. 单选、多选、全选及批量取消共用持久化路径；同进程读改写和解锁窗口串行化。
3. 成功回调在提交和恢复目录保护之后发出；恢复失败明确报错，已提交时清理旧缓存。
4. 修改前重新读取规则；读取/解析失败不按空规则覆盖文件。保留高级规则、intent-filter，处理多段重复组件。
5. 采用用户提供的图片作为桌面、启动页和设置页图标；修复设计系统资源名称前缀。
6. 使用独立包名与 RSA-4096 发行密钥；缺少发行签名时不再偷偷回退 Debug 签名。
7. 关闭对外导出的组件控制 Provider，避免其他 App 直接调用本应用的特权操作入口。
   这意味着旧的第三方 Provider 集成不再适用于此独立发行版。

## 验证与边界

- 跨模块 FOSS 单测：96 个测试集、655 项测试；2026-10-17 使用 `-RepeatTests` 强制复跑后，失败/错误/跳过均为 0。
  最终汇总见 [test-summary.json](../dist/test-summary.json)。
- Release 构建、签名验证及相关模块格式检查成功。完整 Release Lint 无 Error，有 26 条 Warning、6 条 Hint；不将“构建通过”写成“全项目零缺陷”。
- 已在 Android 17 root 手机上验证此前优化候选包的真实 root 命令：两种初始属性、覆盖/删除、
  故障注入、8 个并发操作、Parcel 往返及测试文件清理均通过。
- [真机记录](../dist/DEVICE_VERIFICATION.txt)保留候选包确切哈希。最终包增加 Provider 导出限制后重建；
  本轮手机未连接，最终包的安装、全部 UI 路径和最终包真机复测尚未完成。
- Windows 未运行 Linux 截图基线比较；没有把关闭截图比对当作截图测试通过。
- finally/NonCancellable 覆盖正常结束、异常和协程取消，不覆盖断电、SIGKILL、内核崩溃或其他 root 软件并发修改属性。
- 批量按包提交，不是跨所有应用的数据库事务；后续包失败不会撤销先前包。
- Provider 不受 IFW 控制；空选择不会修改目录。高级 intent 规则仍可能阻止已移除组件选择器的组件。
- 本次复查重点是全部补丁、规则落盘链路、图标、发行签名和公开入口；
  并非对整个上游所有历史行为、多用户模式及所有 ROM 的完整认证。

## 构建、签名与回滚

JDK 21、Android SDK、Gradle Wrapper 已验证可用。使用仓库脚本：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-release.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-release.ps1 -RepeatTests
powershell -NoProfile -ExecutionPolicy Bypass -File dist/rollback.ps1
```

最后一条默认只检查反向补丁。加 `-Apply` 才回滚本次源码；不触碰手机规则、已安装 App 或密钥。
[source.patch](../dist/source.patch)保留完整源码差异；原 `gradlew.bat` 的用户已有改动未混入提交。

发行私钥保存在仓库外 `%USERPROFILE%\.android-signing\blocker`，不提交。
务必单独备份 keystore 并把密码保存在可靠的密码管理器；Windows DPAPI 凭据文件不等于跨账号可用的密码备份。
后续版本沿用同一包名和密钥，并递增 versionCode，才能正常覆盖升级。

## AdClose

参见 [完整使用指南](ADCLOSE-GUIDE.zh-CN.md)。AdClose 与 Blocker 分层工作，不是本次 APK 内置的第二个模块。
