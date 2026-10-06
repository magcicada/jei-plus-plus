# JEI++

JEI++ 是一个仅客户端运行的 JEI 附属模组，为 JEI 增加配方目录、配方树、制作助手、流体支持、存储网络库存读取、创造物品分类栏和可折叠物品分组。它不添加方块、物品、配方或其他游戏内容。

## 中文说明

### 配方目录与书签

- 多候选输入槽点击后打开分页目录，目录中的候选可继续查看配方、用途和标签；标签页不被视为配方。
- 顶部箭头/页码区域滚轮翻整页，配方名称行滚轮切换配方页，分类图标行滚轮切换分类。
- 书签配方查看保留全部匹配配方和用途，并把收藏配方及其分类放在首位。
- 同时开启 JEI 自身的书签优先排序和 `preferRecipeBookmarkOnOutput=true` 时，收藏输出槽会收藏配方；输入槽始终收藏物品。`hideRecipeBookmarkButton` 独立控制 JEI 原生收藏按钮是否隐藏。
- 多输出配方按鼠标所在的具体输出物品确定收藏目标。

### 默认配方

- 真实 JEI 配方提供配方树按钮和默认配方按钮，按钮只显示图标。
- 默认配方按输出物品保存，支持多输出配方的部分选择，写入 `config/jei_plus_plus/recipe_defaults.json`。
- 原木/去皮原木到木板、铁粒到铁锭等不适合作为通用默认的配方不会被自动选中，但仍可手动设为默认。

### 配方树与制作助手

- 提供类似 EMI 的节点、连线、分类、输入输出和数量视图，支持展开、折叠、拖动、缩放、自动适配完整视图和重新居中。
- 节点可以替换配方、选择根输出、在树内固定具体输入候选，右键解除固定；等价输入槽会合并。
- 未固定候选时，所有候选参与库存统计、背包高亮和 JEI 配方转移。候选优先使用背包或网络中已有的物品，并递归寻找可制作材料链，不按库存评分擅自回退配方。
- 默认配方、配方书签和物品书签参与构建，收藏的中间产物也会应用。
- 制作模式支持目标份数、批量数量、循环保护、深度/节点限制、总耗材、副产物、剩余材料、拖动、缩放和完整视图适配。最终产物、总耗材和副产物共用竖直中心线，最终/中间/基础及缺少/已有状态使用不同颜色。
- 配方树书签按最终产物、中间产物、基础材料分行显示；即使已有也保留，数量实时读取背包和网络库存。
- 机械动力序列组装会把每个额外循环中被 JEI 隐藏的物品/流体输入补入配方树，按实际循环次数计算总耗材。
- 普通左键只转移当前步骤直接材料，不递归；启用 `automaticCraftingEnabled` 后 Ctrl+左键只递归转移缺少的步骤。即时工作方块在可行时取出中间产物，有加工时间的机器只放入材料。
- 节点工作方块右下角 `+` 执行 JEI 风格的当前配方转移，Shift+点击转移完整数量；完成后关闭配方树和其他 JEI 配方覆盖层并回到工作方块界面。
- JEI 作弊模式下，配方树书签不拦截最终产物和中间产物的原生拿取一个/一组物品操作。
- 总配方树按钮只在 JEI/配方树界面显示，左键打开或返回、右键清除，E/Esc 返回。活动配方树保存到 `config/jei_plus_plus/recipe_tree_session.json`，可跨存档、重启和 JEI 重载恢复；主动切换或清除时删除。

### 流体

- 树和书签直接显示流体，不统一转成桶。
- 明确要求桶的配方保持桶输入；要求流体的配方把流体和可用容器作为候选，可用 `+` 固定候选。支持流体输入/输出、多输出选择、流体能力槽位、容器转移和网络流体库存。
- 1000 mB=1 B；低于 1000 mB 显示 mB，达到 1000 mB 后以一位小数显示 B。已有数量显示实际数量，不截断到需求上限。

### 存储网络

AE2 样板终端“制作样板”按钮：Shift+左键按当前配方树自底向上批量制作，并跳过网络中已有的相同编码样板；Ctrl+左键即使已有相同样板也强制制作。未固定候选的合成配方会将整张样板设为“可替换”（AE2 处理样板不支持此开关）。空白样板取自终端空白样板槽，产物进入背包或空的终端输出槽；空白样板耗尽或没有空间时停止。此新增操作要求服务端也安装 JEI++，其他客户端功能不受影响。

可选客户端反射集成支持 AE2、Refined Storage RS1/RS2、超越维度和集成动力/集成终端。网络物品与流体参与配方树候选、数量、配方转移和高亮，并优先显示在终端列表前方；共享快照、修订号和按需扫描避免在非 AE2 终端重复探测 AE2。

转移优先使用终端客户端接口、官方数据包或通用容器点击协议；支持的终端可从网络取材并在背包放不下时回存产物。缺少可选模组、API 变化或不支持的界面会安全跳过。

### 创造物品分类栏

- 在 JEI 物品列表上方显示全部物品和创造模式分类。
- 分类栏支持循环翻页；右键打开分类选择弹窗，每行显示四个分类，左键直接切换，右键置顶或取消置顶。
- 置顶分类排在未置顶分类之前，后置顶的分类显示在更前面；可用 `creativeTabPageNumberEnabled` 单独控制页码显示。
- 页码不占用普通物品槽位；选择弹窗绘制在底层物品贴图和高亮之上，弹窗打开时不显示底层物品提示。

### 物品分组

- 内置羊毛、地毯、混凝土、陶瓦、玻璃、木板、原木、台阶、楼梯、墙、门、工具、矿石、锭、粒、植物、铁轨、告示牌等分组，可反复展开和折叠。
- 同一注册名不同 NBT/组件的物品可折叠，例如药水和附魔书。
- 支持物品列表、Tag 和正则表达式 JSON 分组，分组可设置优先级、名称、排除项和启用状态；每个默认分组有独立 TOML 开关，`mixNamespaceGroups` 控制命名空间混组。
- 检测到 JEI Tag Groups、JEI Groups、Collapsible Groups 等外部分组模组时自动停用 JEI++ 物品变换，避免重复分组。

### 性能与兼容

- 配方候选、按配方键缓存的配方快照、配方状态、布局、背包/网络快照和流体访问器使用缓存，按游戏刻和修订号刷新，减少渲染期间的重复扫描。
- 当前打开的容器物品会计入配方树库存并按需求高亮；分类选择弹窗打开时会屏蔽底层 JEI、背包和容器物品的 tooltip。
- 递归搜索具备深度、访问量和循环保护，仅用于配方树和默认配方。
- 兼容多种 JEI 书签、配方布局、物品列表和渲染路径，并提供 Minecraft 1.20.1 Forge 实现。
- JEI 重载、存档切换、终端切换、可选模组缺失或 API 变化时安全清理和恢复运行时状态。

## 配置

客户端配置文件：`config/jei_plus_plus-client.toml`

| 配置项 | 默认值 | 作用 |
| --- | --- | --- |
| `recipeTreeEnabled` | `true` | 配方树与制作助手总开关 |
| `automaticCraftingEnabled` | `true` | 允许配方树书签 Ctrl+左键递归转移缺少步骤 |
| `preferRecipeBookmarkOnOutput` | `true` | 在 JEI 书签优先排序开启时收藏输出配方 |
| `hideRecipeBookmarkButton` | `true` | 隐藏 JEI 原生添加到书签按钮 |
| `creativeTabBarEnabled` | `true` | 显示创造物品分类栏 |
| `creativeTabPageNumberEnabled` | `true` | 显示分类栏当前页码 |
| `recipeIngredientDirectoryEnabled` | `true` | 启用点击配方多候选槽位时打开物品目录的功能 |
| `pinnedCreativeTabs` | `[]` | 分类弹窗置顶分类列表；通常通过右键分类调整 |
| `stackGroupingEnabled` | `true` | 物品分组总开关 |
| `nbtGroupingEnabled` | `true` | 折叠不同 NBT/组件 |
| `tagGroupingEnabled` | `true` | 启用 Tag 分组 |
| `jsonGroupingEnabled` | `true` | 启用 JSON 分组 |
| `mixNamespaceGroups` | `true` | 默认分组是否混合命名空间 |
| `defaultGroups.<分组名>` | `true` | 独立控制一个内置分组 |

默认配方：`config/jei_plus_plus/recipe_defaults.json`

配方树会话：`config/jei_plus_plus/recipe_tree_session.json`

JSON 分组：`config/jei_plus_plus/stack_groups/*.json`

JSON 分组示例：

```json
{
  "groups": [
    {
      "id": "mypack:shiny_things",
      "type": "jei_plus_plus:group",
      "name": "mypack.group.shiny_things",
      "enabled": true,
      "priority": 10,
      "contents": ["minecraft:diamond", "#c:glass_blocks"],
      "exclusions": ["minecraft:purple_stained_glass"]
    },
    {
      "id": "mypack:ores",
      "type": "jei_plus_plus:regex",
      "regex": "minecraft:.*_ore",
      "priority": 5
    }
  ]
}
```

支持旧版单对象格式，也支持顶层数组或 `{ "groups": [...] }` 在同一个 JSON 中写多个分组。支持 `jei_plus_plus:group`（物品 ID 或 Tag）、`jei_plus_plus:tag` 和 `jei_plus_plus:regex`（完整 `namespace:path` 匹配）。`name` 可为翻译键或直接文本，`priority` 越大越优先。

## 版本与依赖

| 游戏版本 | 加载器 | Java | JEI 开发依赖 | 模组版本 |
| --- | --- | --- | --- | --- |
| 1.20.1 | Forge 47.4.22 | 17 | JEI 15.59.0.210 | 1.0.7-1.20.1 |

声明的 JEI 兼容范围从 15.19.5.99 起，表中版本是当前开发依赖。

JEI 是可选客户端依赖；服务端不需要安装 JEI++，模组不提供服务端游戏逻辑。

## English

JEI++ is a client-only JEI addon. It adds no blocks, items, recipes, or other gameplay content.

### Directories, bookmarks, and defaults

Multi-candidate recipe slots open a paged directory for recipes, usages, and tags. The top page band scrolls by full pages, while the recipe-title and category-icon rows switch recipe pages and categories. Bookmarked recipes keep every matching recipe and usage visible, with the bookmarked recipe and category first. Output recipe bookmarking requires both JEI bookmark priority and `preferRecipeBookmarkOnOutput`; input slots bookmark items, and `hideRecipeBookmarkButton` independently controls JEI's native button.

Default recipes are stored per output, including partial multi-output choices. Generic log-to-plank and nugget-to-ingot defaults are skipped but remain manually selectable.

### Recipe Tree and Crafting Assistant

The EMI-style tree supports nodes, connectors, categories, quantities, expansion, collapse, recipe replacement, output selection, in-tree candidate locking, right-click unlock, panning, zooming, fit-to-view, target quantities, leftovers, by-products, cycle protection, and depth/node limits. Equivalent inputs are merged. Unlocked candidates all participate in counts, highlighting, and JEI transfer; selection prefers inventory/network entries and recursively searches craftable chains without inventory-score recipe fallback.

Tree bookmarks show final products, intermediates, and base materials on separate rows even when already owned. Plain left-click transfers direct inputs only; Ctrl+left-click transfers missing recursive steps when automatic crafting is enabled. Instant stations can take intermediate outputs, while timed machines receive inputs only. The node `+` transfers the recipe and Shift+click requests the full amount, then closes the tree and JEI overlays. JEI cheat-mode clicks for final/intermediate entries are preserved. The active tree persists in `recipe_tree_session.json` across worlds, restarts, and JEI reloads.

### Fluids and storage networks

On AE2's Encode button, Shift+left-click creates the selected tree's patterns from leaves to root and skips identical installed patterns. Ctrl+left-click forces creation even if the network already has them. Unpinned alternatives enable substitution for the entire crafting pattern; processing patterns have no substitution switch. Blanks come from the terminal's blank slot, and results go to inventory or the empty output slot. The batch stops when blanks or space run out. This new action requires server-side JEI++; other client-only features remain available without it.

Fluids remain fluids in trees and bookmarks. Explicit bucket recipes stay bucket-based; fluid recipes can use fluid or compatible container candidates. Fluid inputs/outputs, multi-output selection, fluid-capability slots, containers, and network fluid quantities are supported. 1000 mB equals 1 B; actual amounts are displayed without clamping.

Optional reflective client integrations support AE2, Refined Storage RS1/RS2, Beyond Dimensions, and Integrated Dynamics/Integrated Terminals. Network items and fluids participate in planning, counts, highlighting, and transfer and are placed first in supported terminal views. Transfers prefer client APIs, official packets, or generic container clicks, with safe fallback when an integration is unavailable.

### Creative tabs, item groups, and compatibility

The creative tab bar provides all-items and creative categories with wrapping pages. Right-click opens a four-column picker; left-click selects a category and right-click pins or unpins it. Pinned categories appear first, and `creativeTabPageNumberEnabled` controls the page indicator. The item-directory behavior for multi-candidate recipe slots can be toggled with `recipeIngredientDirectoryEnabled`. Built-in expandable groups cover common material and tool families, NBT/component variants such as potions and enchanted books, and configurable item/tag/regex JSON groups. Every default group has its own switch; `mixNamespaceGroups` controls namespace mixing. JEI++ disables its grouping transform when another grouping addon is present.

Candidate, per-recipe snapshot, recipe-status, layout, inventory/network, and fluid caches reduce repeated scans; items in the open container count toward recipe-tree inventory and highlighting. Recursive searches are bounded. Multiple JEI paths are supported on Forge 1.20.1, and optional integrations fail safely on missing mods or changed APIs.

### Version and dependency

| Minecraft | Loader | Java | JEI development dependency | Mod version |
| --- | --- | --- | --- | --- |
| 1.20.1 | Forge 47.4.22 | 17 | JEI 15.59.0.210 | 1.0.7-1.20.1 |

The declared JEI compatibility floor is 15.19.5.99; the table lists the current development dependency.

JEI is an optional client dependency. JEI++ does not require installation on a server.
