ALTER TABLE s_runtime_ticket
    ADD COLUMN official_service_id bigint NULL AFTER relay_version_ref,
    ADD COLUMN official_service_revision bigint NULL AFTER official_service_id;
