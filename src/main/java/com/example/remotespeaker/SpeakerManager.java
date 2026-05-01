package com.example.remotespeaker;

import com.example.remotespeaker.data.SpeakerSavedData;
import com.example.remotespeaker.memo.RecordingManager;
import com.example.remotespeaker.memo.RecordingSession;
import com.example.remotespeaker.memo.VoiceMemo;
import com.example.remotespeaker.skin.SpeakerRole;
import com.example.remotespeaker.skin.SpeakerSkins;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * In-memory cache of all known mic / speaker units (across all loaded levels)
 * plus the tick-based scheduler that sequences chime → voice-memo playback.
 *
 * Source of truth for placement / label / role data is each level's
 * {@link SpeakerSavedData}; this class is hydrated on server start and mutated
 * alongside saved-data writes on placement / break / label commands.
 */
public class SpeakerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("RemoteSpeaker");

    public static final SpeakerManager INSTANCE = new SpeakerManager();

    /** Idle "alive" particle period in ticks (~5 s). */
    private static final long IDLE_WIGGLE_PERIOD = 100L;

    /** "Now recording" particle period in ticks. */
    private static final long SENDER_PARTICLE_PERIOD = 4L;

    /** "Now playing" particle period in ticks. */
    private static final long RECEIVE_PARTICLE_PERIOD = 4L;

    private static final DustParticleOptions REC_DUST =
        new DustParticleOptions(0xFF3030, 1.2f);

    private final Map<UUID, SpeakerEntry> speakerRegistry = new ConcurrentHashMap<>();

    private final PriorityBlockingQueue<ScheduledPlayback> playbackQueue =
        new PriorityBlockingQueue<>();

    private final PriorityBlockingQueue<CueSound> cueQueue =
        new PriorityBlockingQueue<>();

    /** Speaker UUID → server tick at which the active "playing" indicator stops. */
    private final Map<UUID, Long> playingUntilTick = new ConcurrentHashMap<>();

    private SpeakerManager() {}

    // -------------------------------------------------------------------------
    // Hydration / mutation
    // -------------------------------------------------------------------------

    /** Pull every entry from the level's saved data into the in-memory cache. */
    public void hydrateFromLevel(ServerLevel level) {
        SpeakerSavedData data = SpeakerSavedData.get(level);
        for (Map.Entry<BlockPos, SpeakerSavedData.SpeakerRecord> e : data.all()) {
            SpeakerSavedData.SpeakerRecord r = e.getValue();
            speakerRegistry.put(r.uuid(),
                new SpeakerEntry(r.uuid(), level, e.getKey(), r.label(), r.role()));
        }
        LOGGER.info("[RemoteSpeaker] Hydrated {} units from level {}",
            data.all().size(), level.dimension().identifier());
    }

    /** Register a freshly placed mic or speaker. Writes through to saved data. */
    public void register(ServerLevel level, BlockPos pos, UUID speakerUUID,
                         String label, SpeakerRole role) {
        BlockPos immPos = pos.immutable();
        SpeakerSavedData.get(level).put(immPos,
            new SpeakerSavedData.SpeakerRecord(speakerUUID, label, role));
        speakerRegistry.put(speakerUUID,
            new SpeakerEntry(speakerUUID, level, immPos, label, role));
        LOGGER.info("[RemoteSpeaker] Registered {} {} at {} (label='{}', total={})",
            role, speakerUUID, immPos.toShortString(), label, speakerRegistry.size());
    }

    /** Remove a unit by position. Writes through to saved data. */
    public void unregister(ServerLevel level, BlockPos pos) {
        SpeakerSavedData data = SpeakerSavedData.get(level);
        Optional<SpeakerSavedData.SpeakerRecord> rec = data.get(pos);
        rec.ifPresent(r -> speakerRegistry.remove(r.uuid()));
        data.remove(pos);
    }

    public void updateLabel(UUID speakerUUID, String label) {
        SpeakerEntry entry = speakerRegistry.get(speakerUUID);
        if (entry == null) return;
        entry.label = label;
        SpeakerSavedData.get(entry.level).updateLabel(speakerUUID, label);
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /**
     * Returns all SPEAKER-role entries (receivers) other than the given exclude UUID.
     * Drops stale entries (block no longer ours) lazily.
     */
    public List<SpeakerEntry> getAllReceivers(UUID excludeUUID) {
        List<SpeakerEntry> result = new ArrayList<>();
        List<SpeakerEntry> stale = new ArrayList<>();
        for (SpeakerEntry entry : speakerRegistry.values()) {
            if (entry.role != SpeakerRole.SPEAKER) continue;
            if (entry.speakerUUID.equals(excludeUUID)) continue;
            if (!isStillRegistered(entry)) { stale.add(entry); continue; }
            result.add(entry);
        }
        for (SpeakerEntry e : stale) unregister(e.level, e.pos);
        return Collections.unmodifiableList(result);
    }

    public Optional<SpeakerEntry> getByUUID(UUID uuid) {
        SpeakerEntry e = speakerRegistry.get(uuid);
        if (e == null) return Optional.empty();
        if (!isStillRegistered(e)) {
            unregister(e.level, e.pos);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    private boolean isStillRegistered(SpeakerEntry entry) {
        SpeakerRole r = SpeakerSkins.roleOf(
            entry.level.getBlockState(entry.pos),
            entry.level.getBlockEntity(entry.pos));
        return r == entry.role;
    }

    // -------------------------------------------------------------------------
    // Memo delivery (called from server tick)
    // -------------------------------------------------------------------------

    public void scheduleMemoDelivery(VoiceMemo memo, long currentTick) {
        SpeakerEntry target = speakerRegistry.get(memo.targetSpeakerUUID());
        if (target == null || target.role != SpeakerRole.SPEAKER) {
            LOGGER.warn("[RemoteSpeaker] Delivery failed: target {} not a registered speaker",
                memo.targetSpeakerUUID());
            return;
        }
        // Labelled targets broadcast to every same-label SPEAKER unit (case-insensitive).
        // Unlabelled stays single-target.
        List<SpeakerEntry> targets = new ArrayList<>();
        String label = target.label;
        if (label != null && !label.isEmpty()) {
            for (SpeakerEntry e : speakerRegistry.values()) {
                if (e.role != SpeakerRole.SPEAKER) continue;
                if (label.equalsIgnoreCase(e.label) && isStillRegistered(e)) targets.add(e);
            }
        }
        if (targets.isEmpty()) targets.add(target);

        for (SpeakerEntry t : targets) {
            scheduleJingle(t, currentTick, label);
            playbackQueue.add(new ScheduledPlayback(currentTick + 20, t.speakerUUID, memo));
        }
    }

    public void tick(MinecraftServer server) {
        long now = server.getTickCount();

        // Fire scheduled cue sounds (jingle notes + tail-out clicks)
        while (!cueQueue.isEmpty() && cueQueue.peek().targetTick <= now) {
            CueSound c = cueQueue.poll();
            c.level.playSound(null, c.pos, c.sound, SoundSource.BLOCKS, c.volume, c.pitch);
        }

        // Start scheduled voice-memo playbacks
        while (!playbackQueue.isEmpty() && playbackQueue.peek().targetTick() <= now) {
            ScheduledPlayback task = playbackQueue.poll();
            SpeakerEntry target = speakerRegistry.get(task.targetSpeakerUUID());
            if (target == null || target.role != SpeakerRole.SPEAKER) continue;
            playMemo(task.memo(), target);
            // PCM frames are 20 ms each → 0.4 ticks per frame.
            long durationTicks = Math.max(1L,
                (long) Math.ceil(task.memo().pcmFrames().size() * 0.4));
            playingUntilTick.put(target.speakerUUID, now + durationTicks);
            // Schedule a tail-out click at the moment audio ends.
            cueQueue.add(new CueSound(now + durationTicks, target.level, target.pos,
                SoundEvents.LEVER_CLICK, 0.4f, 1.6f));
        }

        // Receive-side note particles
        if (now % RECEIVE_PARTICLE_PERIOD == 0 && !playingUntilTick.isEmpty()) {
            playingUntilTick.entrySet().removeIf(e -> e.getValue() < now);
            for (Map.Entry<UUID, Long> e : playingUntilTick.entrySet()) {
                SpeakerEntry t = speakerRegistry.get(e.getKey());
                if (t == null) continue;
                double note = t.level.getRandom().nextInt(25) / 24.0;
                t.level.sendParticles(ParticleTypes.NOTE,
                    t.pos.getX() + 0.5, t.pos.getY() + 0.9, t.pos.getZ() + 0.5,
                    0, note, 0.0, 0.0, 1.0);
            }
        }

        // Sender-side red dust above mic heads of active recordings
        if (now % SENDER_PARTICLE_PERIOD == 0) {
            for (RecordingSession session : RecordingManager.INSTANCE.activeSessions()) {
                if (session.isStopped()) continue;
                BlockPos pos = session.sourceSpeakerPos;
                SpeakerEntry mic = findEntryAt(pos);
                if (mic == null) continue;
                mic.level.sendParticles(REC_DUST,
                    pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5,
                    2, 0.08, 0.05, 0.08, 0.0);
            }
        }

        // Idle "alive" wiggle on every registered unit
        if (now % IDLE_WIGGLE_PERIOD == 0) {
            for (SpeakerEntry e : speakerRegistry.values()) {
                if (e.role == SpeakerRole.MIC) {
                    e.level.sendParticles(ParticleTypes.END_ROD,
                        e.pos.getX() + 0.5, e.pos.getY() + 0.85, e.pos.getZ() + 0.5,
                        1, 0.05, 0.0, 0.05, 0.0);
                } else {
                    double note = e.level.getRandom().nextInt(25) / 24.0;
                    e.level.sendParticles(ParticleTypes.NOTE,
                        e.pos.getX() + 0.5, e.pos.getY() + 0.9, e.pos.getZ() + 0.5,
                        0, note, 0.0, 0.0, 0.4);
                }
            }
        }
    }

    private SpeakerEntry findEntryAt(BlockPos pos) {
        for (SpeakerEntry e : speakerRegistry.values()) {
            if (e.pos.equals(pos)) return e;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Audio helpers
    // -------------------------------------------------------------------------

    /**
     * Three-note ascending chime (root, major-3rd, perfect-5th) over 8 ticks.
     * Root pitch is shifted by a small per-label hash so different rooms get
     * recognisable signatures.
     */
    private void scheduleJingle(SpeakerEntry target, long currentTick, String label) {
        float root = 1.0f + labelPitchOffset(label);
        SoundEvent chime = SoundEvents.NOTE_BLOCK_CHIME.value();
        cueQueue.add(new CueSound(currentTick,     target.level, target.pos, chime, 1.0f, root));
        cueQueue.add(new CueSound(currentTick + 4, target.level, target.pos, chime, 1.0f, root * 1.26f));
        cueQueue.add(new CueSound(currentTick + 8, target.level, target.pos, chime, 1.0f, root * 1.5f));
    }

    /** ±15% pitch shift derived from label hash. Empty/null label → 0. */
    private static float labelPitchOffset(String label) {
        if (label == null || label.isEmpty()) return 0f;
        int bucket = Math.floorMod(label.toLowerCase().hashCode(), 7) - 3;  // -3..+3
        return bucket * 0.05f;
    }

    private void playMemo(VoiceMemo memo, SpeakerEntry target) {
        VoicechatServerApi api = RemoteSpeakerPlugin.serverApi;
        if (api == null) return;

        short[] pcm = memo.toFlatArray();

        LocationalAudioChannel ch = api.createLocationalAudioChannel(
            UUID.randomUUID(),
            api.fromServerLevel(target.level),
            api.createPosition(
                target.pos.getX() + 0.5,
                target.pos.getY() + 0.5,
                target.pos.getZ() + 0.5)
        );
        if (ch == null) return;

        ch.setCategory("remote_speaker");
        ch.setDistance(16);

        AudioPlayer player = api.createAudioPlayer(ch, api.createEncoder(), pcm);
        player.startPlaying();
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    public static class SpeakerEntry {
        public final UUID speakerUUID;
        public final ServerLevel level;
        public final BlockPos pos;
        public volatile String label;
        public final SpeakerRole role;

        public SpeakerEntry(UUID speakerUUID, ServerLevel level, BlockPos pos,
                            String label, SpeakerRole role) {
            this.speakerUUID = speakerUUID;
            this.level = level;
            this.pos = pos;
            this.label = label;
            this.role = role;
        }

        public String displayName() {
            return (label == null || label.isEmpty()) ? pos.toShortString() : label;
        }
    }

    private record ScheduledPlayback(long targetTick, UUID targetSpeakerUUID, VoiceMemo memo)
        implements Comparable<ScheduledPlayback> {
        @Override
        public int compareTo(ScheduledPlayback o) {
            return Long.compare(this.targetTick, o.targetTick);
        }
    }

    private record CueSound(long targetTick, ServerLevel level, BlockPos pos,
                            SoundEvent sound, float volume, float pitch)
        implements Comparable<CueSound> {
        @Override
        public int compareTo(CueSound o) {
            return Long.compare(this.targetTick, o.targetTick);
        }
    }
}
