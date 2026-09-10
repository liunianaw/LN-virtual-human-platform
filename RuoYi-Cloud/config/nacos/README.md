# 裁剪后的 Nacos 配置

在目标 namespace 的 DEFAULT_GROUP 中建立配置：根目录每个 .yml 文件名就是 Data ID，类型 YAML；sentinel-ruoyi-gateway.json 对应 Data ID sentinel-ruoyi-gateway，类型 JSON。默认环境为 dev，改环境时同步 Data ID 后缀及 SPRING_PROFILES_ACTIVE。

默认导入 application、auth、gateway、system、job 五个 YAML 及 Sentinel JSON。不要继续使用原始 ry_config SQL 里的配置值。网关只开放 /auth、/system、/schedule 三组路由，关闭服务发现自动路由，保留原有认证与验证码处理。

运行 Java 进程前设置 NACOS_ADDR（默认 127.0.0.1:8848）、DB_URL、DB_USER、DB_PASSWORD、REDIS_HOST/PORT/PASSWORD。单数据源替代 dynamic.datasource，不能保留旧 common 中排除 Druid 自动配置的设置。容器对应环境已写入 Compose。

COS 配置在 system 本地 application.yml 中从 COS_ENABLED/REGION/BUCKET/SECRET_ID/SECRET_KEY/SESSION_TOKEN/READ_URL_SECONDS 读取。默认 disabled，未配置时头像返回默认图，上传明确报错；开启时必须提供桶名（含 APPID）、区域和凭证。私有桶返回短期签名链接，COS CORS 需允许后台来源 GET/HEAD 以供头像裁剪读取；真实云上传、浏览器预览尚待联调。凭证不填入这些模板，不提交到 Git。

devtools/：只在独立开发环境导入 ruoyi-gen-dev.yml，并以其中的完整 ruoyi-gateway-dev.yml 替换该环境网关配置；TOOL_DB_URL/USER/PASSWORD 必填且指向工具库。为生成器准备 gen_table/gen_table_column（参考原始业务 SQL 的对应两张表），不复制整套账号表。然后构建/启动 devtools 后端及前端，执行可选菜单启用脚本并分配权限。生产环境保留默认网关配置。

monitoring/：按需导入 ruoyi-monitor-dev.yml，设置 MONITOR_USER/MONITOR_PASSWORD；手动打开 9100 控制台。默认不启动 Admin 或 Sentinel 控制台。Nacos 管理入口、业务鉴权与这些运维控制台分开配置。

这些文件是运行配置模板，不是已经部署的服务。Compose 尚未进行真实容器启动验收；原始基础设施镜像版本和 Nacos 初始化方式会在工程骨架阶段统一固定。
