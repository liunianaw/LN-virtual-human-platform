package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.voice.VoiceBinding;
import com.ruoyi.session.business.BusinessSessionService;
import com.ruoyi.session.business.BusinessSystemClient;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeAuthorizationVoiceTest
{
    @Test void referenceVoiceUsesFrozenBindingAndRevocationStillApplies() throws Exception
    {
        var json = new ObjectMapper();
        var jdbc = mock(JdbcTemplate.class);
        var system = mock(BusinessSystemClient.class);
        var rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(anyInt())).thenReturn(1L);
        when(rs.getTimestamp(anyInt())).thenReturn(Timestamp.from(Instant.now().plusSeconds(300)));
        when(rs.getString(2)).thenReturn("token");
        when(rs.getString(3)).thenReturn("BUSINESS_KEY");
        for (int column : new int[]{9,11,13}) when(rs.getString(column)).thenReturn("ACTIVE");
        when(rs.getString(19)).thenReturn("[\"session:read\",\"speak:write\"]");
        when(rs.getString(21)).thenReturn("");
        var binding = new VoiceBinding("1","COSYVOICE3","1",1,"model","revision","capability",
            "reference:901","zh-CN",Map.of(),"901","参考文本",null,false,"1","http://127.0.0.1:8012");
        when(rs.getString(24)).thenReturn(json.writeValueAsString(binding));
        doAnswer(call -> ((ResultSetExtractor<?>)call.getArgument(1)).extractData(rs))
            .when(jdbc).query(anyString(),any(ResultSetExtractor.class),eq(10L));
        var current = mock(BusinessSystemClient.Snapshot.class);
        when(current.applicationEpoch()).thenReturn(1L);
        when(current.allowedScopes()).thenReturn(List.of("session:read","speak:write"));
        when(system.check(1,1,1)).thenReturn(current);
        var access = new RuntimeAuthorization(mock(RuntimeTokenCodec.class),mock(BusinessSessionService.class),system,jdbc,json);
        var grant = access.verify(10);
        assertEquals("reference:901",grant.principal().voice().providerVoiceRef());
        assertEquals(binding,grant.principal().voice().execution());
        when(rs.getString(24)).thenReturn(null);
        when(rs.getString(21)).thenReturn("Cherry");
        assertEquals("Cherry",access.verify(10).principal().voice().providerVoiceRef());
        when(rs.getString(9)).thenReturn("REVOKED");
        assertThrows(RuntimeProblem.class,()->access.verify(10));
    }
}
