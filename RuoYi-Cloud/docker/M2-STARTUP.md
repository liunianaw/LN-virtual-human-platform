# M2 Compose preparation

Copy `.env.example` to ignored `docker/.env` and fill every required secret through the deployment secret store. Do not place keys in Compose, Nacos properties, shell history, or this document.

`sh deploy.sh m2` starts MySQL, Redis, Nacos, RabbitMQ, system, session, media API/Worker, auth, gateway and nginx in their declared health dependency order. System requires private COS configuration and forces `COS_ENABLED=true`; COS is an external service, not a local Compose container.

`ruoyi-m2-config-gate` runs before system/session/media. Image generation and official TTS each default to explicit `false`; switching one to `true` requires its endpoint and runtime secret. The gate prints only a variable name, never a value. The media Worker has no synthetic HTTP health check: use its structured runtime logs and the real message/claim path during acceptance.

Container logs use local rotation. Media temporary processing uses a bounded `tmpfs` mounted at `/tmp/ruoyi-media` with `noexec,nosuid,nodev` and restrictive mode; generated intermediate files must not be persisted in source mounts. Actual acceptance still requires configured private COS, official provider/TTS credentials as applicable, published Nacos configuration, and real platform/message integration.
