package com.example.remotespeaker.memo;

import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-player mutable recording state. Not thread-safe on its own —
 * RecordingManager synchronises all access via ConcurrentHashMap operations.
 */
public class RecordingSession {

    /** Maximum PCM frames to accumulate (~12 seconds at 50 packets/sec). */
    public static final int MAX_FRAMES = 600;

    public final UUID playerUUID;
    public final String playerName;
    public final UUID targetSpeakerUUID;
    public final BlockPos targetSpeakerPos;
    /** Position of the mic head the player tapped to start this recording. */
    public final BlockPos sourceSpeakerPos;
    /** Display label of the chosen target (or group), used for the recording actionbar. */
    public final String targetDisplay;

    private final List<short[]> frames = new ArrayList<>();
    private boolean stopped = false;
    private OpusDecoder decoder;

    public RecordingSession(UUID playerUUID, String playerName,
                            UUID targetSpeakerUUID, BlockPos targetSpeakerPos,
                            BlockPos sourceSpeakerPos, String targetDisplay) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.targetSpeakerUUID = targetSpeakerUUID;
        this.targetSpeakerPos = targetSpeakerPos.immutable();
        this.sourceSpeakerPos = sourceSpeakerPos.immutable();
        this.targetDisplay = targetDisplay == null ? "" : targetDisplay;
    }

    /**
     * Adds a decoded PCM frame to this session.
     * @return false if the cap was already reached (caller should auto-stop)
     */
    public boolean addFrame(short[] pcm) {
        if (stopped || frames.size() >= MAX_FRAMES) return false;
        frames.add(pcm);
        return true;
    }

    public void setDecoder(OpusDecoder decoder) { this.decoder = decoder; }

    public OpusDecoder getDecoder() { return decoder; }

    public void stop() {
        stopped = true;
        if (decoder != null) { decoder.close(); decoder = null; }
    }

    public boolean isStopped() {
        return stopped;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    /** Builds an immutable VoiceMemo from the accumulated frames. */
    public VoiceMemo buildMemo() {
        return new VoiceMemo(playerUUID, playerName, targetSpeakerUUID, List.copyOf(frames));
    }
}
