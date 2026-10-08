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
    private long maxTemporaryAudioBytes = 128L * 1024 * 1024;
    public long getMaxTemporaryAudioBytes() { return maxTemporaryAudioBytes; }
    public void setMaxTemporaryAudioBytes(long value) { maxTemporaryAudioBytes = value; }
    private Duration temporaryAudioTtl = Duration.ofMinutes(15);
    private String temporaryAudioDirectory = System.getProperty("java.io.tmpdir") + "/ln-session-audio";
    private Provider official = new Provider();

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

    public static class Provider
    {
        private boolean enabled;
        private Duration timeout = Duration.ofSeconds(30);
        private String endpoint;
        private String model;
        private String voice;
        private int concurrency = 2;
        private int queueCapacity = 8;
        private Duration queueTimeout = Duration.ofSeconds(10);

        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int value) { concurrency = value; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int value) { queueCapacity = value; }
        public Duration getQueueTimeout() { return queueTimeout; }
        public void setQueueTimeout(Duration value) { queueTimeout = value; }

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
