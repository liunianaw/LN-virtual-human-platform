-- Frozen semantics only; credentials remain in p_secret and are resolved at dispatch.
ALTER TABLE p_voice_version
  ADD COLUMN provider_type varchar(64) NULL,
  ADD COLUMN execution_binding json NULL,
  ADD COLUMN fallback_voice_version_id bigint NULL,
  ADD COLUMN reference_asset_id bigint NULL,
  ADD KEY idx_voice_fallback (fallback_voice_version_id),
  ADD CONSTRAINT fk_voice_fallback FOREIGN KEY (fallback_voice_version_id) REFERENCES p_voice_version(id);

UPDATE p_voice_version v JOIN p_official_service s ON s.id=v.official_service_id
SET v.provider_type='DASHSCOPE_QWEN_TTS',
    v.execution_binding=json_object('voiceVersionId',cast(v.id as char),'providerType','DASHSCOPE_QWEN_TTS',
      'serviceId',cast(s.id as char),'serviceRevision',cast(json_unquote(json_extract(v.official_config_snapshot,'$.revision')) as unsigned),
      'modelId',v.model_id,'modelRevision',v.model_id,'capabilityVersion','qwen-bridge-v1',
      'providerVoiceRef',v.voice_code,'language',coalesce(v.language_code,'zh-CN'),'parameters',v.parameters,
      'referenceAssetId',null,'referenceText',null,'fallback',null,'allowVoiceChange',false,'policyVersion','1','endpoint',s.endpoint)
WHERE v.service_type='OFFICIAL' AND s.provider_code IN ('DASHSCOPE_BEIJING','DASHSCOPE_QWEN_TTS');

-- Mapping is deliberately TTS-only; ASR keeps its existing provider code.
UPDATE p_official_service SET provider_code='DASHSCOPE_QWEN_TTS'
WHERE capability='TTS' AND provider_code='DASHSCOPE_BEIJING';

ALTER TABLE p_resource_reference DROP CHECK ck_p_resource_reference_holder_type;
ALTER TABLE p_resource_reference ADD CONSTRAINT ck_p_resource_reference_holder_type
  CHECK (holder_type IN ('APP_CURRENT','SESSION','GENERATION','VOICE_VERSION'));
ALTER TABLE p_resource_reference DROP CHECK ck_p_resource_reference_resource_type;
ALTER TABLE p_resource_reference ADD CONSTRAINT ck_p_resource_reference_resource_type
  CHECK (resource_type IN ('APPLICATION_SNAPSHOT','AVATAR_VERSION','VOICE_VERSION','SKILL','VOICE_REFERENCE'));

ALTER TABLE p_call_record
  ADD COLUMN fact_kind varchar(16) NOT NULL DEFAULT 'LOGICAL',
  ADD COLUMN voice_task_id bigint NULL,
  ADD COLUMN voice_attempt_id bigint NULL,
  ADD COLUMN voice_service_id bigint NULL,
  ADD COLUMN logical_operation_key varchar(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD KEY idx_call_voice_task (account_id,voice_task_id,id),
  ADD CONSTRAINT ck_call_fact_kind CHECK (fact_kind IN ('LOGICAL','ATTEMPT'));
ALTER TABLE p_call_record DROP CHECK ck_p_call_record_cost_source;
ALTER TABLE p_call_record ADD CONSTRAINT ck_p_call_record_cost_source
  CHECK (cost_source IN ('UNKNOWN','PROVIDER','CONSOLE','ESTIMATED','SELF_HOSTED','TEST'));
