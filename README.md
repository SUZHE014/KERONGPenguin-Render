# KERONGPenguin-Render

**KERONGPenguin 图片渲染功能的 HuHoBot 附属** —— 把 [KERONGPenguin](https://github.com/SUZHE014/KERONGPenguin) 主线版的五张 QQ 群图片卡片及其配套设施移植到 [HuHoBotPenguin](https://github.com/HuHoBot/PenguinAgent) 体系：装上本附属后，在 QQ 群里就能用 KERONGPenguin 同款的精美图片卡片查询服务器信息。

> QQ 连接、扫码登录、群管理、绑定系统、白名单等由 HuHoBotPenguin 统一负责；本附属专注图片渲染，绑定关系直接复用 HuHoBot 自己的绑定数据。

## 功能一览

### QQ 群指令（全部渲染图片回复）

| 指令 | 说明 | 与 HuHoBot 内置重名 |
|------|------|:---:|
| `查信息` | 玩家生涯统计卡片（绑定玩家；`查信息 @成员` 可查他人） | 是 |
| `查在线` / `在线` | 在线玩家列表卡片（>60 人自动分页，每页独立抽背景） | 是 |
| `查性能` | 服务器性能监控卡片（TPS / MSPT / 内存 / CPU / 在线人数） | 否 |
| `在线排行榜` | 总在线时长排行（全量玩家，分页） | 否 |
| `财富榜` | 金币财富排行（需 Vault 经济；经济名称可配置别名） | 否 |

- 图片经 QQ 官方机器人被动回复通道 base64 直传，**不占用群主动消息配额**；
- 与 HuHoBot 内置重名的指令（查在线 / 查信息）默认由**本附属接管**，可在配置里逐个关闭回落内置行为；
- 指令自动进入 HuHoBot 的 QQ 指令面板与 `/帮助` 列表；
- 支持 PlaceholderAPI 变量覆盖（玩家名 / 时长 / 金币 / 点券 / 称号 / TPS / 自定义项）、Vault 金币、DeluxeTags / PlayerTitle 称号、PlayerPoints 点券、spark 性能数据。

### 游戏内命令（OP）

```
/hbkrg help     查看帮助
/hbkrg reload   重载附属配置 + 重新应用指令接管 + 重同步 QQ 指令面板
/hbkrg info     查看附属与 HuHoBot 版本、指令注入状态
/hbkrg mem      附属内存与缓存诊断
/hbkrg bpng     查看可下载的背景图片包清单（源自 KERONGPenguin 官网图片库）
/hbkrg bpng <包ID>   下载图片包到 img/（执行中可再执行一次中断）
/hbkrg bpngsz yes|no 一键切换全部卡片是否使用 img/ 自定义背景图
```

## 环境要求

- 服务端：Spigot / Paper / Folia **1.18+**（`api-version: 1.18`，声明 `folia-supported`）
- **HuHoBotPenguin 1.21.0+**（硬依赖，附属编译对照 1.21.0 发行 JAR）
- 可选软依赖：Vault（金币 / 财富榜）、DeluxeTags / PlayerTitle（称号）、PlayerPoints（点券）、PlaceholderAPI（变量）、spark（性能数据）

## 安装

1. 先安装并配置好 HuHoBotPenguin（QQ 机器人连接、绑定系统）；
2. 把 `KERONGPenguin-Render-x.y.z.jar` 放进 `plugins/`；
3. 重启服务器。附属会把 QQ 指令注入 HuHoBot 分发链并同步指令面板。

### 从 KERONGPenguin 本体迁移

服务器此前用 KERONGPenguin 本体的话，附属首次启动会自动把 `plugins/KERONGPenguin/QUUID/` 里的玩家统计数据（生涯时长 / 击杀 / 死亡 / 距离等）导入自己的 `stats/` 目录——`查信息` 与 `在线排行榜` 的历史数据无缝延续（只读导入，不改动本体文件；QQ 绑定需在 HuHoBot 里重新绑定）。

## 重名指令接管说明

HuHoBot 内置的 `查在线`（自带在线列表渲染）与 `查信息`（OpenId 文本查询）与本附属重名。本附属通过把自己插到 HuHoBot 指令分发链**最前端**实现接管：

```yaml
override-commands:
  查在线: true   # true（默认）= 本附属图片卡片；false = HuHoBot 内置行为
  查信息: true
```

- 接管后，QQ 指令面板上的同名按钮也由本附属提供；
- 改完执行 `/hbkrg reload` 即时生效；
- HuHoBot 执行 `/huhobot reload` 完整重启后，附属看门狗会在 10 秒内自动重新接管。

## 配置

附属配置位于 `plugins/KERONGPenguinRender/config.yml`，结构与 KERONGPenguin 的渲染段一致（`render.*` 灰度 / 模糊度 / 冷却 / 缓存 / 线程、`leaderboard.*`、`coins-leaderboard.*`、`image-download.*`、`variable-fetch.*`、`economy-alias`、`messages.*` 模板映射），另有本附属专属的 `override-commands` 段。全部注释见配置文件本身，改完 `/hbkrg reload` 生效。

消息模板位于 `plugins/KERONGPenguinRender/Markdown/`（19 个 `.md`，可自行编辑）。

## 从源码构建

```bash
# 1. 下载 HuHoBot 发行 JAR（编译对照物）到 libs/
mkdir -p libs
curl -L -o libs/HuHoBot-Penguin_Spigot-1.21.0.jar \
  https://github.com/HuHoBot/PenguinAgent/releases/download/v1.21.0/HuHoBot-Penguin_Spigot-1.21.0.jar

# 2. 构建（JDK 21）
./gradlew clean shadowJar

# 3. 独立冒烟（渲染五类卡片并校验）
./gradlew smokeRender
```

产物：`build/libs/KERONGPenguin-Render-x.y.z.jar`

## 许可证

[GPL-3.0](LICENSE)

- 渲染管线与数据采集移植自 [KERONGPenguin](https://github.com/SUZHE014/KERONGPenguin)（GPL-3.0）；
- 运行时依赖 [HuHoBotPenguin](https://github.com/HuHoBot/PenguinAgent)（AGPL-3.0，`compileOnly`，不参与分发）与其内置的 kloping QQ SDK。

## 下载

见 [Releases](../../releases)。
