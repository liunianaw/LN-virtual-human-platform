-- Only newly created tasks receive a completion step; historical tasks stay unchanged.
ALTER TABLE p_generation_step
    DROP CHECK ck_p_generation_step_step_type,
    ADD CONSTRAINT ck_p_generation_step_step_type CHECK
        (step_type IN ('EXTRACT_REFERENCE','GENERATE_BASE','GENERATE_ACTION','COMPLETE_CHARACTER','PACKAGE','QA'));
