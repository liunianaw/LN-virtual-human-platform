# 数字人开源项目复用清单

日期：2026-09-08。依据：负责人明确要求充分复用数字人开源项目，允许只借鉴其中部分功能；不自行部署模型的约定继续有效。项目用于毕设和作品展示，保留上游来源、许可及修改记录，方便论文引用。

## 已拉取并检查源码

| 项目 / 本地目录 | 来源 | 当前 SHA |
|---|---|---|
| upstream/LiveTalking | https://github.com/lipku/LiveTalking | c4f8c16a86bdc4d217782cac52fb431dc5bca7b0 |
| upstream/MuseTalk | https://github.com/TMElyralab/MuseTalk | 0a89dec45a0192b824e3cf4daf96c239440c5ed8 |
| upstream/TalkingHead | https://github.com/met4citizen/TalkingHead | eed58d198076a7e1e825f804802921c4d3804d46 |

本轮是源码检查与模块选取，没有安装这些项目的完整依赖、启动模型或调用付费 API。下载仓库附带素材不表示将它们用作本平台官方角色。下列“可抽取”是静态分析结论，尚未通过独立运行验证；“参考”表示需要按本平台协议适配实现，不宣称已经集成。

## 1. LiveTalking：优先参考实时语音链路

| 已查文件 / 入口 | 实际能力 | 本平台落点与处理 |
|---|---|---|
| tts/qwentts.py：QwenTTS、回调、_on_audio_data | 云端千问实时 TTS、音频增量事件、PCM 分块和重采样 | 参考官方 TTS 适配及开发者转接示例。用户厂商 Key 仍只放开发者后端，不能照搬为平台集中保存 |
| tts/base_tts.py：BaseTTS、put_msg_txt、flush_talk | 文本队列、合成调度及暂停 | 参考会话服务的 TTS 队列；替换为本平台 turn_id、取消语义和有界队列，不直接复制 queue 内部 clear 的并发处理 |
| llm.py：llm_response | OpenAI-compatible 流式回复，按标点聚合后提交 TTS | 参考分句算法，适配 Java WebClient 和开发者转接协议；补齐历史、取消、工具调用等已有需求 |
| avatars/base_avatar.py：put_audio_file、flush_talk、is_speaking | 输入音频处理、停止语音链路、说话状态 | 参考 SDK / 会话事件映射；整个 BaseAvatar 与推理依赖耦合，不整体导入 |
| streamout/webrtc.py | 音视频输出接口与缓冲查询 | 后续视频输出扩展参考；不因此将当前 WebSocket + Canvas 方案改成 WebRTC |

TTS 目录还包括 azure.py、tencent.py、doubao.py 等适配文件。本轮详细读取了千问与基类，其他厂商尚未逐项验证。优先复用有明确对应需求的逻辑，不把目录存在等同于兼容性已验证。

## 2. MuseTalk：借鉴媒体处理，拆开模型边界

| 已查文件 / 入口 | 实际能力 | 本平台落点与处理 |
|---|---|---|
| scripts/preprocess.py：convert_video、segment_video、extract_audio | FFmpeg 转码、切片、从视频提取 WAV | 视频素材处理时可抽取，使用独立工具模块；原脚本顶层导入 torch / 人脸模型，不能直接整体 import |
| scripts/realtime_inference.py：video2imgs、图像序列合成与音频合并段落 | 视频提帧、按 fps 合成视频、合并已有音轨 | media-service 的视频素材适配及导出参考；沿用参数数组调用 FFmpeg，避免直接复制 shell 字符串命令 |
| musetalk/utils/blending.py：get_crop_box、get_image_blending | 区域裁剪及已有掩码下的图像贴合 | 将来需要局部图像合成时可抽取，依赖 Pillow/NumPy 等；不产生新动作，也不是自动去背景工具 |
| musetalk/utils/audio_processor.py | 为口型推理准备音频特征，与 Whisper / torch 耦合 | 本期不抽取为音频通用模块；它不提供独立文字转语音能力 |

MuseTalk 在本轮检查的核心路径中消费已有音频并生成口型视频。“音频合成”需区分文字转语音与把已有音频合入视频：后者可借鉴其 FFmpeg 流程，前者优先参考 LiveTalking 的云 TTS 适配。

## 3. TalkingHead：借鉴浏览器音频时序与动作接口

已查 modules/talkinghead.mjs：speakAudio、speakText、stopSpeaking、streamStart、streamAudio、streamInterrupt、streamStop、playGesture。

- streamStart / streamAudio：参考流式音频缓存、实际播放起点以及 onAudioStart/onAudioEnd 回调，供 SDK 驱动 speaking/idle。
- streamInterrupt / streamStop：参考停止音频、清理动画队列和恢复 idle 的行为；补上本平台轮次标识与旧数据丢弃。
- playGesture：参考显式动作接口设计，映射到本平台 playAction；当前仍遵循单动作播放。
- 该项目使用全身 3D 角色及骨骼/口型动画，不能直接播放我们的六帧二维图集。保留 Canvas 播放器，抽取或参考音频调度部分，不整体引入 Three.js 角色系统。

## 落地顺序与验收

1. 若依仍负责管理后台、认证和基础权限，先跑通工程骨架。
2. 接入现有已验证的云端动作生成与素材加工，视频处理有需求时抽取 MuseTalk 对应函数。
3. 做会话链路时，优先参考 LiveTalking 云 TTS、分句及打断；做 SDK 时参考 TalkingHead 的音频时钟和状态回调。
4. 每次移植记录原项目 SHA、文件/函数、修改内容和许可证；验证提取模块不依赖模型权重，测试取消后无旧音频重播、分段顺序和队列上限。

平台自己的工作集中在资产、应用、权限、任务持久化、开发者转接协议和 SDK 整合。开源通用能力先检查再实现，避免重复造轮子；上游演示代码仍需适配账号隔离、密钥边界与可靠任务状态。

## 本仓库已验证逻辑复用（2026-09-14）

`validation/avatar_lab/prompts.py` 与 `provider.py`（最近相关提交 `5a7474e`）的八动作纯品红提示词、`prepare_reference` / `layout_guide` 几何约束，移入正式 `ruoyi-media/providers/avatar_prompts.py` 和 `worker/platform_http.py`。正式 Worker 以 COS 字节输入代替本地路径；不引入验证工具的预算账本、CLI 或本地密钥读取。单动作真实生成、透明图集处理、COS 写入与回读哈希已经验证；全八动作与业务汇总仍待验收。
