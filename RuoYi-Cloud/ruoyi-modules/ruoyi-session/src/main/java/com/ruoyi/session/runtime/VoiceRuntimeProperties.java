package com.ruoyi.session.runtime;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Non-secret operational limits. Provider credentials are deliberately absent. */
@ConfigurationProperties("ln.session.voice")
public class VoiceRuntimeProperties
{
    private int maxCodePointsPerSegment = 200;
    private int maxBufferedSegments = 2;
    private long maxAudioBytes = 5L * 1024 * 1024;
    private Duration temporaryAudioTtl = Duration.ofMinutes(15);
    private Provider official = new Provider();
    private Provider relay = new Provider();

    public int getMaxCodePointsPerSegment()
    {
        return maxCodePointsPerSegment;
    }

    public void setMaxCodePointsPerSegment(int maxCodePointsPerSegment)
    {
        this.maxCodePointsPerSegment = maxCodePointsPerSegment;
    }

    public int getMaxBufferedSegments()
    {
        return maxBufferedSegments;
    }

    public void setMaxBufferedSegments(int maxBufferedSegments)
    {
        this.maxBufferedSegments = maxBufferedSegments;
    }

    public long getMaxAudioBytes()
    {
        return maxAudioBytes;
    }

    public void setMaxAudioBytes(long maxAudioBytes)
    {
        this.maxAudioBytes = maxAudioBytes;
    }

    public Duration getTemporaryAudioTtl()
    {
        return temporaryAudioTtl;
    }

    public void setTemporaryAudioTtl(Duration temporaryAudioTtl)
    {
        this.temporaryAudioTtl = temporaryAudioTtl;
    }

    public Provider getOfficial()
    {
        return official;
    }

    public void setOfficial(Provider official)
    {
        this.official = official;
    }

    public Provider getRelay()
    {
        return relay;
    }

    public void setRelay(Provider relay)
    {
        this.relay = relay;
    }

    public static class Provider
    {
        private boolean enabled;
        private Duration timeout = Duration.ofSeconds(30);

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public Duration getTimeout()
        {
            return timeout;
        }

        public void setTimeout(Duration timeout)
        {
            this.timeout = timeout;
        }
    }
}
