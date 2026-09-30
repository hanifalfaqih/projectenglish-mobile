import { describe, it, expect } from "vitest";
import { isWav, pcm16MonoToWav } from "./audio.js";

describe("voice audio helpers", () => {
  it("wraps PCM in a valid WAV container", () => {
    const pcm = new Uint8Array([1, 2, 3, 4]);
    const wav = pcm16MonoToWav(pcm, 16000);
    expect(wav.length).toBe(44 + 4);
    expect(isWav(wav)).toBe(true);
    expect(wav.readUInt32LE(24)).toBe(16000);
    expect(wav.readUInt16LE(34)).toBe(16);
    expect([...wav.subarray(44)]).toEqual([1, 2, 3, 4]);
  });

  it("rejects non-WAV bytes", () => {
    expect(isWav(new Uint8Array([1, 2, 3]))).toBe(false);
    expect(isWav(Buffer.from("NOTAWAVFILE!"))).toBe(false);
  });
});
