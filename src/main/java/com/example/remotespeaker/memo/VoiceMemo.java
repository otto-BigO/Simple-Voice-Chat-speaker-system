package com.example.remotespeaker.memo;

import java.util.List;
import java.util.UUID;

/**
 * Immutable record representing a completed voice memo ready for delivery.
 */
public record VoiceMemo(
    UUID senderUUID,
    String senderName,
    UUID targetSpeakerUUID,
    List<short[]> pcmFrames
) {
    /**
     * Concatenates all PCM frames into a single flat array for use with SVC AudioPlayer.
     * Called once at playback time to avoid repeated allocations during recording.
     */
    public short[] toFlatArray() {
        int total = 0;
        for (short[] frame : pcmFrames) total += frame.length;
        short[] out = new short[total];
        int i = 0;
        for (short[] frame : pcmFrames) {
            System.arraycopy(frame, 0, out, i, frame.length);
            i += frame.length;
        }
        return out;
    }
}
