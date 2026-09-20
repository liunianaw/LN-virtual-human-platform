package com.ruoyi.system.asset.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = { })
@Pattern(regexp = "[A-Za-z0-9._:-]{1,64}")
@Target({ ElementType.ANNOTATION_TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestId
{
    String message() default "requestId 格式无效";
    Class<?>[] groups() default { };
    Class<? extends Payload>[] payload() default { };
}
