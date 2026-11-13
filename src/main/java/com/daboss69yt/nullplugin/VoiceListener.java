package com.daboss69yt.nullplugin;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hears the owner through Simple Voice Chat. Their microphone packets are decoded to audio, cut into
 * phrases at pauses, sent to Groq's speech-to-text, and the text goes to the Commander as if typed.
 * Only the owner is listened to, and only phrases starting "null" or "commander" are acted on.
 */
public final class VoiceListener implements VoicechatPlugin {
    private static final int SAMPLE_RATE = 48000;
    private static final long PAUSE_MS = 1000;
    private static final int MIN_SAMPLES = SAMPLE_RATE / 2; // ignore clicks under half a second

    private final NullControllerPlugin plugin;
    private final CommanderChat chat;
    private final Map<UUID, Phrase> phrases = new HashMap<>();
    private final Map<UUID, de.maxhenkel.voicechat.api.opus.OpusDecoder> decoders = new HashMap<>();
    private VoicechatServerApi api;

    /** One phrase in progress: decoded samples, and when the last packet arrived. */
    private static final class Phrase {
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long lastMs;
        int samples;
    }

    public VoiceListener(NullControllerPlugin plugin, CommanderChat chat) {
        this.plugin = plugin;
        this.chat = chat;
        new BukkitRunnable() {
            @Override
            public void run() {
                flushQuietPhrases();
            }
        }.runTaskTimerAsynchronously(plugin, 10L, 10L);
    }

    @Override
    public String getPluginId() {
        return "nullcontroller";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> api = e.getVoicechat());
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private synchronized void onMicrophone(MicrophonePacketEvent e) {
        if (api == null) return;
        UUID id = e.getSenderConnection().getPlayer().getUuid();
        Player p = Bukkit.getPlayer(id);
        if (p == null || !chat.isOwner(p)) return; // only the owner is heard
        var decoder = decoders.computeIfAbsent(id, k -> api.createDecoder());
        short[] samples = decoder.decode(e.getPacket().getOpusEncodedData());
        if (samples == null || samples.length == 0) return;
        Phrase phrase = phrases.computeIfAbsent(id, k -> new Phrase());
        ByteBuffer buf = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) buf.putShort(s);
        phrase.pcm.writeBytes(buf.array());
        phrase.samples += samples.length;
        phrase.lastMs = System.currentTimeMillis();
    }

    /** Called off the main thread. Any phrase that has gone quiet is sent for transcription. */
    private synchronized void flushQuietPhrases() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Phrase> entry : new HashMap<>(phrases).entrySet()) {
            Phrase phrase = entry.getValue();
            if (now - phrase.lastMs < PAUSE_MS) continue;
            phrases.remove(entry.getKey());
            if (phrase.samples < MIN_SAMPLES) continue;
            byte[] wav = wav(phrase.pcm.toByteArray());
            UUID id = entry.getKey();
            String key = plugin.getConfig().getString("groq-api-key", "");
            if (key == null || key.isBlank()) continue;
            try {
                String text = Speech.transcribe(key, plugin.getConfig().getString("groq-stt-model", "whisper-large-v3-turbo"), wav);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) chat.spoken(p, text);
                });
            } catch (Exception ex) {
                plugin.getLogger().warning("Voice transcription failed: " + ex);
            }
        }
    }

    /** Wraps 48 kHz mono 16-bit PCM in a WAV header. */
    static byte[] wav(byte[] pcm) {
        ByteBuffer out = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
        out.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        out.putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2);
        out.putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        out.put(pcm);
        return out.array();
    }
}
