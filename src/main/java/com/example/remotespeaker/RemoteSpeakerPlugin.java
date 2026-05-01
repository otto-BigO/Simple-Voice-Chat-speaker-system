package com.example.remotespeaker;

import com.example.remotespeaker.memo.RecordingManager;
import com.example.remotespeaker.memo.RecordingSession;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class RemoteSpeakerPlugin implements VoicechatPlugin {

    public static volatile VoicechatServerApi serverApi;

    @Override
    public String getPluginId() {
        return RemoteSpeakerMod.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {}

    @Override
    public void registerEvents(EventRegistration reg) {
        reg.registerEvent(VoicechatServerStartedEvent.class, e -> {
            serverApi = e.getVoicechat();
            serverApi.registerVolumeCategory(
                serverApi.volumeCategoryBuilder()
                    .setId("remote_speaker")
                    .setName("Remote Speakers")
                    .setDescription("Audio from remote speaker blocks")
                    .build()
            );
        });

        reg.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    /**
     * Fires on the SVC thread for every incoming microphone packet.
     * We only decode the Opus data if the sender is actively recording a voice
     * memo — skipping decode otherwise saves significant CPU.
     */
    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (serverApi == null || event.getSenderConnection() == null) return;

        ServerPlayer player =
            (ServerPlayer) event.getSenderConnection().getPlayer().getEntity();
        UUID playerUUID = player.getUUID();

        byte[] opusData = event.getPacket().getOpusEncodedData();
        if (opusData == null || opusData.length == 0) return;

        RecordingSession session = RecordingManager.INSTANCE.getActiveSession(playerUUID);
        if (session == null || session.getDecoder() == null) return;

        short[] audio = session.getDecoder().decode(opusData);
        if (audio == null || audio.length == 0) return;

        RecordingManager.INSTANCE.onMicrophonePacket(playerUUID, audio);
    }
}
