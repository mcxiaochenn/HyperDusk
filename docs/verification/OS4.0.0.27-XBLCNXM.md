# HyperOS 4 验证记录

## 基线与环境

- 上游基线：`c9d471b446f7e3ffca89fbfdf0fda26a6d99b6e8`
- 设备：Xiaomi 17 Pro（`pandora`，型号 `25098PN5AC`）
- 系统：Android 17 / SDK 37，HyperOS `OS4.0.0.27.XBLCNXM`
- LSPosed：IT `v2.1.1-it (7846)`
- 本文不记录设备序列号或私人数据。

## Hook 点

`system_server` 中的 `com.android.server.input.shortcut.singlekeyrule.MiuiSingleKeyRuleManager` 与 `VolumeDownKeyRule`。模块复制原生音量减规则到 `KEYCODE_VOLUME_UP(24)`，沿用系统 300ms、最大双击计数 2 及锁屏/息屏、无活跃音频、非注入事件、非副屏相机占用条件。动作调用 `ShortCutActionsUtils.getInstance(context).triggerFunction("turn_on_torch", "double_click_volume_up", Bundle.EMPTY, false)`。

缺少类或签名时只写入 Xposed 日志并停止安装（fail closed），不回退到全局按键拦截。

## 验证矩阵

| 项目 | 结果 |
| --- | --- |
| `none` 默认值保持音量行为 | 待实体按键验证 |
| 亮屏解锁单击/双击调高音量 | 待实体按键验证 |
| 锁屏/息屏双击音量上键开关手电筒 | 待实体按键验证 |
| 播放音频时不触发手电筒 | 待实体按键验证 |
| ADB 注入按键不触发 | 待设备验证 |
| 音量减、电源键、截图组合键无回归 | 待实体按键验证 |
| 关闭设置后无需卸载恢复 | 待设备验证 |

## 留痕

本地执行命令、APK SHA-256 和 GitHub Actions 运行链接在发布构建后补录。回退方式为在 LSPosed 禁用模块并重启，或卸载 APK；无需修改系统分区或 LSPosed 数据目录。
