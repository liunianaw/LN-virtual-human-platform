package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.account.domain.RegistrationBenefits;

class RegistrationBenefitsTest
{
    @Test
    void missingSettingsFailAndExplicitZeroIsAllowed()
    {
        for (String key : List.of(RegistrationBenefits.CONCURRENCY_KEY, RegistrationBenefits.STORAGE_MB_KEY,
            RegistrationBenefits.POINTS_KEY))
        {
            assertThrows(ServiceException.class, () -> RegistrationBenefits.validate(key, null));
            assertThrows(ServiceException.class, () -> RegistrationBenefits.validate(key, ""));
        }
        assertEquals(new RegistrationBenefits(0, 0, 0), RegistrationBenefits.from("0", "0", "0"));
    }

    @ParameterizedTest
    @CsvSource({
        "platform.registration.initialConcurrency,-1",
        "platform.registration.initialConcurrency,2.5",
        "platform.registration.initialConcurrency,10001",
        "platform.registration.initialStorageMb,1.5",
        "platform.registration.initialStorageMb,9223372036854775807",
        "platform.registration.initialPoints,0.001",
        "platform.registration.initialPoints,9223372036854775807"
    })
    void fractionalAndOverflowingValuesAreRejected(String key, String value)
    {
        assertThrows(ServiceException.class, () -> RegistrationBenefits.validate(key, value));
    }
}
