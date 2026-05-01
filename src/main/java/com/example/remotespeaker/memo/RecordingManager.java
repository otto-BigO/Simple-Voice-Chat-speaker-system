package com.example.remotespeaker.memo;

import com.example.remotespeaker.RemoteSpeakerPlugin;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Singleton managing per-player recording sessions and the memo delivery queue.
 *
 * Thread-safety: activeSessions is a ConcurrentHashMap because MicrophonePacketEvent
 * fires on the SVC thread, not the server thread. deliveryQueue is a
 * ConcurrentLinkedQueue for the same reason. drainDeliveryQueue() is only ever
 * called from the server tick thread.
 */
public class RecordingManager {

    public static final RecordingManager INSTANCE = new RecordingManager();

    /** At most one active session per player UUID. */
    private final Map<UUID, RecordingSession> activeSessions = new ConcurrentHashMap<>();

    /** Completed memos waiting to be routed on the next server tick. */
    private final Queue<VoiceMemo> deliveryQueue = new ConcurrentLinkedQueue<>();

    private RecordingManager() {}

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts a new recording session for the player, targeting the given speaker.
     * Silently replaces any existing session (player switched target mid-recording).
     */
    public void startRecording(UUID playerUUID, String playerName,
                               UUID targetSpeakerUUID, BlockPos targetSpeakerPos,
                               BlockPos sourceSpeakerPos, String targetDisplay) {
        RecordingSession session = new RecordingSession(
            playerUUID, playerName, targetSpeakerUUID, targetSpeakerPos,
            sourceSpeakerPos, targetDisplay);
        if (RemoteSpeakerPlugin.serverApi != null) {
            session.setDecoder(RemoteSpeakerPlugin.serverApi.createDecoder());
        }
        activeSessions.put(playerUUID, session);
    }

    /** Read-only view of all active recording sessions (server-tick safe). */
    public Iterable<RecordingSession> activeSessions() {
        return activeSessions.values();
    }

    public RecordingSession getActiveSession(UUID playerUUID) {
        RecordingSession s = activeSessions.get(playerUUID);
        return (s != null && !s.isStopped()) ? s : null;
    }

    /**
     * Finalises the recording and enqueues the memo for delivery.
     * @return true if a session existed and was stopped; false if not recording
     */
    public boolean stopRecording(UUID playerUUID) {
        RecordingSession session = activeSessions.remove(playerUUID);
        if (session == null) return false;
        session.stop();
        if (!session.isEmpty()) {
            deliveryQueue.add(session.buildMemo());
        }
        return true;
    }

    /**
     * Discards the recording without queuing a memo (used on player disconnect
     * or explicit /rspeaker cancel).
     */
    public void cancelRecording(UUID playerUUID) {
        activeSessions.remove(playerUUID);
    }

    // -------------------------------------------------------------------------
    // SVC thread callbacks
    // -------------------------------------------------------------------------

    /** Called from MicrophonePacketEvent on the SVC thread. */
    public boolean isRecording(UUID playerUUID) {
        RecordingSession s = activeSessions.get(playerUUID);
        return s != null && !s.isStopped();
    }

    /**
     * Accumulates a decoded PCM frame. Auto-stops and finalises if the cap
     * (MAX_FRAMES = 600 ≈ 12 seconds) is reached.
     */
    public void onMicrophonePacket(UUID playerUUID, short[] pcm) {
        RecordingSession session = activeSessions.get(playerUUID);
        if (session == null || session.isStopped()) return;

        boolean accepted = session.addFrame(pcm);
        if (!accepted) {
            // Cap reached — auto-finalise
            RecordingSession s = activeSessions.remove(playerUUID);
            if (s != null) {
                s.stop();
                if (!s.isEmpty()) deliveryQueue.add(s.buildMemo());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Server tick interface
    // -------------------------------------------------------------------------

    /**
     * Drains all completed memos from the queue and returns them.
     * Must only be called from the server tick thread.
     */
    public List<VoiceMemo> drainDeliveryQueue() {
        List<VoiceMemo> out = new ArrayList<>();
        VoiceMemo memo;
        while ((memo = deliveryQueue.poll()) != null) out.add(memo);
        return out;
    }
}
