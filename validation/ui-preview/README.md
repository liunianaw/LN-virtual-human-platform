# Application 配置视觉预览

打开 `application.html` 即可预览；无构建步骤、无外部 CDN、无平台接口调用。也可以在仓库根目录执行 `python -m http.server 8768 --bind 127.0.0.1 --directory validation/ui-preview`，访问 http://127.0.0.1:8768/application.html 。

页面所有配置均为示例，刷新即恢复。支持五个配置标签、运行模式、表单编辑、主题切换和窗口透明度调整；发布、调试、凭证与导航按钮仅显示演示反馈。角色动作按钮不播放动画。正式页面字段参考 `RuoYi-Cloud-Vue3/src/views/application/index.vue`，本预览将长配置表单分组为标签供视觉评审，不代表正式交互方案已获确认。

图标复用项目现有 SVG；声音图标暂复用 phone.svg。角色由内置 image_gen 工具原创生成。当前环境使用 CSS 银灰渐变，不加载背景图片；此前生成的图片保留供对照，来源与提示词见下方。

## 当前设计：银灰摄影棚与悬浮操作面板

顶栏贯穿屏幕并承载品牌，侧栏文字靠右、图标在右；取消路由标签栏、菜单内阴影和蓝色四角装饰，仅选中项保留轻背景。应用名称与页面标题合并为一组。

角色位于开放空间，取消角色卡片和实体托盘，使用脚底接触阴影。右侧单一配置面板容纳五个分类，桌面面板内部滚动且操作按钮固定。窄屏改为展示窗口内上下排列，整页不滚动。材质、字体和留白按“环境 / 角色 / 操作面板”三个层次安排，天蓝仅用于交互强调。

本静态视觉评审按用户要求默认透明，不自动跟随操作系统的减少透明效果偏好；页脚提供“实底模式”及窗口透明感调节供对照。正式应用仍须保留系统可访问性偏好，本预览选择不代表生产实现。

## 保留的截图补绘背景（当前未使用）

文件：`assets/game-background-clean.png`。源图：用户上传的游戏截图。仅供本地评审，不代表已取得正式发布所需的素材授权。内置图像工具编辑提示词：

Edit this attached game screenshot into a clean background plate ONLY. Remove all foreground characters, portraits, weapons, all interface panels, buttons, icons, typography, subtitles, numbers and their shadows. Inpaint the areas with the SAME existing background. Preserve the original flat desaturated silver-grey mist studio environment: smooth cool grey upper background, faint whitish misty horizon at lower half, subtly faceted pale grey floor near bottom, almost no saturation. No new architecture, no mountains, no buildings, no blue sky, no dramatic clouds, no pedestal. Match original background color and very low contrast precisely. Output wide landscape clean background only, edge to edge, no black bars.

## 原创背景提示词

Use case: stylized-concept. Asset type: wide desktop web application background, landscape 16:9. Create an original ethereal empty futuristic exhibition environment with an almost infinite pale blue-grey horizon, translucent frosted architectural planes and very subtle faceted glass floor disappearing into low white mist. Soft sky-blue light from upper right, desaturated silver and icy grey, faint powder blue gradients. Extremely spacious and calm. Tiny sparse geometric facets near bottom edges; center and upper two thirds nearly empty with gentle atmospheric tonal depth so UI text can sit over it. Camera straight ahead, minimal perspective ground plane in bottom quarter. High-end restrained game character-selection environment, photorealistic architectural 3D render, delicate ambient shadows, no dramatic clouds, no busy texture, no saturation, no sci-fi machinery. NO characters, NO objects in center, NO text, NO logos, NO symbols, NO UI, NO watermarks.

输出：`assets/cloud-atrium.png`。

## 原创角色提示词

Use case: stylized-concept. Asset type: transparent full body virtual assistant character illustration for a software application configuration preview. Original anime-style young adult female virtual guide, elegant short silver blue bob haircut, calm friendly expression, blue eyes, white fitted futuristic long coat with subtle powder blue panels over charcoal navy short dress, black opaque tights, understated white ankle boots. Small cyan technical details, refined contemporary science fiction costume, no weapon, no sexualization. Standing relaxed three-quarter front view, one hand casually extended at waist as a welcome gesture, complete head and feet visible. High quality 3D anime game character render, soft studio light, crisp silhouette, slender balanced proportions. Transparent background, isolated single character, no environment, no text, no logos, no UI, no pedestal.

输出：`assets/guide.png`。仅供静态角色展台效果展示。
