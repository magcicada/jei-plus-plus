# 更新日志 / Changelog

## 1.0.8a — 2026-10-26

### 中文

- 修复新版 JEI 先按原生输入区域筛选事件，导致配方树按钮和创造物品分类栏无法响应的问题。
- 分类选择弹窗打开时优先接收输入，避免点击穿透到配方、书签或容器；移至最终绘制阶段，避免被配方界面覆盖。
- 恢复左下角配方树按钮的 JEI 原生皮肤、悬停和选中状态；侧栏没有空间时不再绘制悬空按钮。
- 修复分类页码行和分类图标行的滚轮事件被配方翻页处理器提前消耗的问题。
- 恢复额外滚轮导航：物品列表页码区域整页翻页，配方导航行与分类图标行切换。
- 保留旧版 JEI 输入分支，新增真实 JEI 输入场景的无游戏启动回归检查。

### English

- Fixed the recipe-tree button and creative item tabs not responding because newer JEI filters events against native input areas.
- Give the open category picker input priority over recipes, bookmarks, and containers, and render it in the final screen pass so recipes cannot cover it.
- Restore the native JEI skin, hover and selection states of the lower-left recipe-tree button, and hide it when the sidebar has no room.
- Handle category-page and category-icon wheel input before JEI's generic recipe-page handler consumes it.
- Restored extra wheel navigation for full-page ingredient-list paging and recipe/category navigation rows.
- Retained legacy JEI input paths and added headless regression coverage using real JEI input scenes.

## 1.0.8 — 2026-10-25

### 中文

- 额外适配 JEI 15.74.0.229（1.20.1）、19.70.0.458（1.21.1）与 29.47.0.111（26.1.2）的新版输入交互、分类导航和书签接口，保留旧版接口分支。
- 修复多级配方将同一输出槽的互斥候选当作同时产出，导致其他分支复用不存在的副产物而少算材料的问题；真正的多输出槽仍保留副产物。
- 快照使用无焦点的标准布局，并区分不同配方对象，避免界面打开顺序影响缓存内容。
- 修复旧节点需求数量残留和库存统计读取已扣除工作副本的问题；暂停界面内的库存刷新不再依赖游戏时间推进。
- 新增无图形界面的材料计算、JEI 输入桥和注入目标/直接调用兼容性检查。不将构建检查等同于游戏内整合包验证。

### English

- Added compatibility with JEI 15.74.0.229 (1.20.1), 19.70.0.458 (1.21.1), and 29.47.0.111 (26.1.2), including the new input interaction, category navigation, and bookmark paths, while retaining legacy API branches.
- Fixed nested recipes treating mutually exclusive alternatives in one output slot as simultaneous products, which could undercount materials in sibling branches. Genuine separate output slots still contribute byproducts.
- Cache canonical, unfocused recipe layouts and distinguish recipe objects so opening order does not contaminate snapshots.
- Clear stale node demand, retain the original inventory for stock totals, and refresh inventory in paused interfaces without relying on world ticks.
- Added headless material-planning, JEI input bridge, injection-target, and direct-call compatibility checks. Build checks are not an in-game modpack test.

## 1.0.6 — 2026-09-27

### 中文

- 创造物品分类栏支持循环翻页，并新增分类选择弹窗：每行四个分类，左键切换，右键置顶/取消置顶；置顶顺序会影响分类显示顺序。
- 新增页码显示开关，以及配方多候选物品目录功能开关。
- 修复分类弹窗被底层物品贴图、高亮轮廓和 tooltip 遮挡的问题；弹窗打开时阻止选择底层物品。
- 打开容器中的物品现在会参与配方树库存计算，并按配方树需求高亮。
- 缓存配方快照和配方状态，减少多候选配方页面渲染时的重复扫描。
- Forge 1.20.1 与 NeoForge 1.21.1 版本号均更新至 1.0.6。

### English

- Creative item tabs now wrap when paging and include a category picker with four entries per row. Left-click selects a category; right-click pins or unpins it, and pin order determines display order.
- Added separate toggles for the page indicator and the multi-candidate recipe ingredient directory.
- Fixed the category picker being obscured by underlying item sprites, highlight outlines, and tooltips. Underlying items can no longer be selected while the picker is open.
- Items in an open container now count toward recipe-tree inventory and are highlighted according to recipe-tree requirements.
- Cached recipe snapshots and recipe status to reduce repeated scans while rendering recipe pages with many candidates.
- Updated both Forge 1.20.1 and NeoForge 1.21.1 builds to version 1.0.6.
