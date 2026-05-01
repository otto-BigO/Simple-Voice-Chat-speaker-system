package com.example.remotespeaker.skin;

import com.mojang.serialization.Codec;

public enum SpeakerRole {
    MIC,
    SPEAKER;

    public static final Codec<SpeakerRole> CODEC = Codec.STRING.xmap(
        s -> "MIC".equalsIgnoreCase(s) ? MIC : SPEAKER,
        SpeakerRole::name
    );
}
