package com.daboss69yt.nullplugin;

import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Speech-to-text through Groq's transcription endpoint. */
public final class Speech {
    private static final String TRANSCRIBE = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private Speech() {
    }

    /** Sends a WAV clip and returns the words spoken in it. */
    public static String transcribe(String key, String model, byte[] wav) throws Exception {
        String boundary = "----nullcontroller" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "model", model);
        field(body, boundary, "response_format", "json");
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n";
        body.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        body.writeBytes(wav);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(TRANSCRIBE))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        return JsonParser.parseString(resp.body()).getAsJsonObject().get("text").getAsString().trim();
    }

    /** Text to speech: returns WAV audio of the line, spoken in the given Orpheus voice. Keep lines short (200 characters max). */
    public static byte[] synthesize(String key, String model, String voice, String text) throws Exception {
        String clipped = text.length() > 200 ? text.substring(0, 200) : text;
        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        body.addProperty("model", model);
        body.addProperty("voice", voice);
        body.addProperty("input", clipped);
        body.addProperty("response_format", "wav");
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/audio/speech"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
        return resp.body();
    }

    /** Reads a WAV clip (16-bit PCM) and returns 48 kHz mono samples, the rate Simple Voice Chat plays at. */
    public static short[] toPcm48(byte[] wav) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int channels = 1, rate = 24000, dataStart = -1, dataLen = 0;
        int pos = 12;
        while (pos + 8 <= wav.length) {
            String id = new String(wav, pos, 4, StandardCharsets.US_ASCII);
            int len = b.getInt(pos + 4);
            if (id.equals("fmt ")) {
                channels = b.getShort(pos + 10);
                rate = b.getInt(pos + 12);
            } else if (id.equals("data")) {
                dataStart = pos + 8;
                dataLen = Math.min(len, wav.length - dataStart);
                break;
            }
            pos += 8 + len + (len & 1);
        }
        if (dataStart < 0) return new short[0];
        int frames = dataLen / (2 * channels);
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += b.getShort(dataStart + (i * channels + c) * 2);
            mono[i] = (short) (sum / channels);
        }
        int outLen = (int) ((long) frames * 48000 / rate);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double src = i * (double) rate / 48000.0;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, frames - 1);
            double f = src - i0;
            out[i] = (short) (mono[i0] * (1 - f) + mono[i1] * f);
        }
        return out;
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        String part = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
        out.writeBytes(part.getBytes(StandardCharsets.UTF_8));
    }
}
