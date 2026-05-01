package com.example.remotespeaker.data;

import com.example.remotespeaker.RemoteSpeakerMod;
import com.example.remotespeaker.skin.SpeakerRole;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Per-level persistent map of (BlockPos → SpeakerRecord) for every placed
 * mic / speaker head that the server treats as Remote Speaker equipment.
 */
public class SpeakerSavedData extends SavedData {

    public record SpeakerRecord(UUID uuid, String label, SpeakerRole role) {
        public static final Codec<SpeakerRecord> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.STRING_CODEC.fieldOf("uuid").forGetter(SpeakerRecord::uuid),
            Codec.STRING.optionalFieldOf("label", "").forGetter(SpeakerRecord::label),
            SpeakerRole.CODEC.optionalFieldOf("role", SpeakerRole.SPEAKER).forGetter(SpeakerRecord::role)
        ).apply(inst, SpeakerRecord::new));
    }

    private record Entry(BlockPos pos, SpeakerRecord record) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
            SpeakerRecord.CODEC.fieldOf("record").forGetter(Entry::record)
        ).apply(inst, Entry::new));
    }

    public static final Codec<SpeakerSavedData> CODEC = Entry.CODEC.listOf()
        .xmap(SpeakerSavedData::fromList, d -> d.toList())
        .fieldOf("speakers")
        .codec();

    public static final SavedDataType<SpeakerSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(RemoteSpeakerMod.MOD_ID, "speakers"),
        SpeakerSavedData::new,
        CODEC,
        DataFixTypes.LEVEL
    );

    private final Map<BlockPos, SpeakerRecord> entries = new HashMap<>();

    public SpeakerSavedData() {}

    private static SpeakerSavedData fromList(List<Entry> list) {
        SpeakerSavedData d = new SpeakerSavedData();
        for (Entry e : list) d.entries.put(e.pos.immutable(), e.record);
        return d;
    }

    private List<Entry> toList() {
        return entries.entrySet().stream()
            .map(e -> new Entry(e.getKey(), e.getValue()))
            .toList();
    }

    public static SpeakerSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(BlockPos pos, SpeakerRecord record) {
        entries.put(pos.immutable(), record);
        setDirty();
    }

    public void remove(BlockPos pos) {
        if (entries.remove(pos) != null) setDirty();
    }

    public Optional<SpeakerRecord> get(BlockPos pos) {
        return Optional.ofNullable(entries.get(pos));
    }

    public Set<Map.Entry<BlockPos, SpeakerRecord>> all() {
        return entries.entrySet();
    }

    /** Update the label for the speaker with the given UUID. No-op if not in this level. */
    public boolean updateLabel(UUID speakerUUID, String label) {
        for (Map.Entry<BlockPos, SpeakerRecord> e : entries.entrySet()) {
            SpeakerRecord r = e.getValue();
            if (r.uuid().equals(speakerUUID)) {
                e.setValue(new SpeakerRecord(speakerUUID, label, r.role()));
                setDirty();
                return true;
            }
        }
        return false;
    }
}
