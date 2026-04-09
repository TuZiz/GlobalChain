# GlobalChain

GlobalChain 是一个面向 `Spigot / Paper / Folia` 的玩家全球市场插件，目标是提供高可配置 GUI 市场、异步 IO、Vault 经济接入，以及对自定义物品的尽可能完整持久化支持。

## 当前特性

- 支持 `Folia`，并对普通 `Spigot / Paper` 兼容运行
- 默认存储为本地 `YAML`，可切换 `MySQL`
- `Vault` 经济接入，余额、扣款、卖家收款统一走 Vault
- `MiniMessage` + `RGB` 语言系统
- 菜单驱动 GUI，支持独立菜单文件配置
- 全服市场、我的在售、待领取箱、分类页、排序页、上架确认页、我的商品编辑页
- 分类子页面筛选
- 排序子页面：最新上架、价格最低、价格最高、即将到期
- 上架确认流程：主手预览、聊天输入售价、确认提交
- 购买成功后物品先进入待领取箱
- 超长 NBT 物品序列化存储，适配大多数自定义物品场景
- 兼容性调试模式，可输出物品编码长度、哈希、PDC 键等信息

## 存储模式

### YAML

- 默认模式
- 适合单服或本地开发测试
- 数据文件位置：`plugins/GlobalChain/storage/local-market.yml`

### MySQL

- 适合跨服、跨节点共享市场
- 需要在 `config.yml` 中把 `storage.mode` 改为 `mysql`
- 连接池使用 `HikariCP`

说明：

- `YAML` 不适合真正的跨服共享市场
- 需要跨服时应使用 `MySQL`

## GUI 页面

- `market-global.yml`：全服市场
- `market-mine.yml`：我的在售
- `market-mailbox.yml`：待领取箱
- `market-filter.yml`：分类目录
- `market-sort.yml`：排序目录
- `market-sell-confirm.yml`：上架确认页
- `market-mine-edit.yml`：我的商品编辑页
- `loading.yml`：异步加载占位页

## 指令

- `/gmarket`
- `/gmarket open`
- `/gmarket mine`
- `/gmarket mailbox`
- `/gmarket balance`
- `/gmarket sell <price>`
- `/gmarket buy <id-prefix>`
- `/gmarket cancel <id-prefix>`
- `/gmarket claim [id-prefix]`
- `/gmarket grant <player|uuid> <amount>`
- `/gmarket reload`

别名：

- `/gc`
- `/market`
- `/globalchain`

## 依赖

- `Vault` 必需
- 需要一个实际的 Vault 经济提供者
  - 例如 `EssentialsX Economy`
  - 或其他已注册到 Vault 的经济插件

## 安装

1. 把 `Vault` 和你的经济插件放入服务器 `plugins` 目录
2. 把 `GlobalChain-1.0-SNAPSHOT.jar` 放入 `plugins` 目录
3. 启动服务器生成默认配置与菜单
4. 按需修改 `config.yml`、`lang/zh_cn.yml`、`menus/*.yml`
5. 修改纯配置或菜单后执行 `/gmarket reload`
6. 修改插件代码后替换新 jar 并重启服务器

## 配置要点

`config.yml` 重点字段：

- `server-id`：当前节点标识
- `storage.mode`：`yaml` 或 `mysql`
- `currency.scale`：小数精度
- `currency.name`：货币名称显示
- `currency.symbol`：货币符号
- `market.max-listings-per-player`：玩家最大在售数
- `market.max-price`：最大价格
- `market.sale-fee-rate`：手续费比例，`0.00` 到 `1.00`
- `market.listing-expire-seconds`：上架过期时间
- `performance.io-threads`：异步 IO 线程数
- `debug.compatibility-mode`：自定义物品兼容调试开关

## 自定义物品兼容说明

本插件使用完整 `ItemStack` 编码存储物品，而不是只保存材质、名称、Lore，因此对以下类型通常有较好兼容性：

- `ItemsAdder`
- `MMOItems`
- 其他通过 `NBT / PDC / ItemMeta` 写入自定义数据的物品

但要注意：

- 原插件必须仍然安装
- 对应物品定义不能被删除或破坏兼容
- 如果源插件自身升级后改变了数据格式，市场无法单独保证百分百恢复

建议在测试服先验证你的自定义物品链路，再上线生产服。

## 调试模式

当 `debug.compatibility-mode: true` 时，会在后台输出兼容性日志，包括：

- 物品类型
- 物品数量
- 编码长度
- 编码哈希
- PDC 键列表
- 自定义名称

适合排查：

- 自定义物品上架后属性变化
- 领取后物品不一致
- 某些物品无法正确恢复

## 构建

环境要求：

- `JDK 21`
- `Gradle 9.x`

构建命令：

```powershell
.\.gradle-dist\gradle-9.3.0\bin\gradle.bat build --no-daemon --console=plain
```

构建产物：

- `build/libs/GlobalChain-1.0-SNAPSHOT.jar`

## 已实现的运行时原则

- 所有数据库和文件 IO 设计为脱离 MC 主线程执行
- GUI 打开流程采用异步加载页
- 购买成功先投递到待领取箱
- 上架时有暂存与确认校验
- 支持恢复任务扫描未完成事务

## 已知边界

- `YAML` 模式不适用于真实跨服共享
- Vault 与市场数据库不是同一个事务系统，因此跨系统无法得到严格数学意义上的单事务原子性证明
- 本插件当前实现的是工程上的强恢复一致性与补偿链路，而不是理论上的全局 exactly-once 证明模型
