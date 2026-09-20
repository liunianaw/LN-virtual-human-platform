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
    private String temporaryAudioDirectory = System.getProperty("java.io.tmpdir") + "/ln-session-audio";
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

    public String getTemporaryAudioDirectory()
    {
        return temporaryAudioDirectory;
    }

    public void setTemporaryAudioDirectory(String temporaryAudioDirectory)
    {
        this.temporaryAudioDirectory = temporaryAudioDirectory;
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
        private String endpoint;
        private String model;
        private String voice;

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

        public String getEndpoint()
        {
            return endpoint;
        }

        public void setEndpoint(String endpoint)
        {
            this.endpoint = endpoint;
        }

        public String getModel()
        {
            return model;
        }

        public void setModel(String model)
        {
            this.model = model;
        }

        public String getVoice()
        {
            return voice;
        }

        public void setVoice(String voice)
        {
            this.voice = voice;
        }
    }
}
