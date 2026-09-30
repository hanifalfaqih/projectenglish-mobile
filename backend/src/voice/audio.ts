/**
 * Minimal audio container helpers for the voice-turn path.
 *
 * Android captures fixed-contract raw PCM (16 kHz, mono, 16-bit LE) and the
 * Qwen ASR endpoint expects a WAV container, so the backend wraps bytes
 * in-memory. No transcoding, no temp files, no new dependencies.
 */

export const VOICE_SAMPLE_RATE_HZ = 16000;
export const VOICE_CHANNELS = 1;
export const VOICE_BITS_PER_SAMPLE = 16;

export function isWav(buffer: Uint8Array): boolean {
  return (
    buffer.length >= 12 &&
    buffer[0] === 0x52 && // R
    buffer[1] === 0x49 && // I
    buffer[2] === 0x46 && // F
    buffer[3] === 0x46 && // F
    buffer[8] === 0x57 && // W
    buffer[9] === 0x41 && // A
    buffer[10] === 0x56 && // V
    buffer[11] === 0x45 // E
  );
}

/** Wrap raw 16-bit mono PCM bytes in a 44-byte WAV container. */
export function pcm16MonoToWav(
  pcm: Uint8Array,
  sampleRateHz: number = VOICE_SAMPLE_RATE_HZ,
): Buffer {
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(VOICE_CHANNELS, 22);
  header.writeUInt32LE(sampleRateHz, 24);
  header.writeUInt32LE((sampleRateHz * VOICE_CHANNELS * VOICE_BITS_PER_SAMPLE) / 8, 28);
  header.writeUInt16LE((VOICE_CHANNELS * VOICE_BITS_PER_SAMPLE) / 8, 32);
  header.writeUInt16LE(VOICE_BITS_PER_SAMPLE, 34);
  header.write("data", 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, Buffer.from(pcm)]);
}
