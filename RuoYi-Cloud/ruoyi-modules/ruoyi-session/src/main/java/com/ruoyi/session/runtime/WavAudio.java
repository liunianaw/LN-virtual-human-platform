package com.ruoyi.session.runtime;
final class WavAudio {
    private WavAudio() { }
    static long requireMonoPcm16Khz(byte[] audio,long maximum) { return com.ruoyi.common.voice.VoiceWav.validate(audio,maximum); }
}
