package com.secure.wificalling.audio;

/**
 * Standard IMA-ADPCM Audio Codec.
 * 4:1 Real-time compression:
 * Compresses 16-bit 16kHz linear PCM (256 kbps) down to 4-bit ADPCM (64 kbps).
 * Pure Java, zero native dependencies, zero latency, runs instantly on Android 5.0 to 17+.
 * Allows VoIP packets to stream reliably across 4G/5G mobile networks without dropouts.
 */
public class AdpcmCodec {

    private static final int[] INDEX_TABLE = {
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    };

    private static final int[] STEP_SIZE_TABLE = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    public static class State {
        public int valprev = 0;
        public int index = 0;
    }

    /**
     * Compresses 16-bit linear PCM byte buffer (little-endian) into 4-bit IMA-ADPCM.
     * Output size = pcmLength / 4.
     */
    public static int encode(byte[] pcmIn, int pcmOffset, int pcmLength, byte[] adpcmOut, int outOffset, State state) {
        int valprev = state.valprev;
        int index = state.index;
        int outIndex = outOffset;
        boolean bufferstep = true;
        int outputbuffer = 0;

        int numSamples = pcmLength / 2;
        for (int i = 0; i < numSamples; i++) {
            int pcmIndex = pcmOffset + (i * 2);
            int val = (pcmIn[pcmIndex] & 0xFF) | (pcmIn[pcmIndex + 1] << 8); // signed 16-bit sample

            int step = STEP_SIZE_TABLE[index];
            int diff = val - valprev;
            int sign = (diff < 0) ? 8 : 0;
            if (sign != 0) diff = -diff;

            int delta = 0;
            int vpdiff = (step >> 3);

            if (diff >= step) {
                delta |= 4;
                diff -= step;
                vpdiff += step;
            }
            step >>= 1;
            if (diff >= step) {
                delta |= 2;
                diff -= step;
                vpdiff += step;
            }
            step >>= 1;
            if (diff >= step) {
                delta |= 1;
                vpdiff += step;
            }

            if (sign != 0) {
                valprev -= vpdiff;
            } else {
                valprev += vpdiff;
            }

            // Clamp sample
            if (valprev > 32767) valprev = 32767;
            else if (valprev < -32768) valprev = -32768;

            delta |= sign;
            index += INDEX_TABLE[delta];
            if (index < 0) index = 0;
            else if (index > 88) index = 88;

            if (bufferstep) {
                outputbuffer = (delta << 4) & 0xF0;
            } else {
                adpcmOut[outIndex++] = (byte) ((delta & 0x0F) | outputbuffer);
            }
            bufferstep = !bufferstep;
        }

        if (!bufferstep) {
            adpcmOut[outIndex++] = (byte) outputbuffer;
        }

        state.valprev = valprev;
        state.index = index;
        return outIndex - outOffset;
    }

    /**
     * Decompresses 4-bit IMA-ADPCM into 16-bit linear PCM byte buffer (little-endian).
     * Output size = adpcmLength * 4.
     */
    public static int decode(byte[] adpcmIn, int inOffset, int inLength, byte[] pcmOut, int outOffset, State state) {
        int valprev = state.valprev;
        int index = state.index;
        int outIndex = outOffset;
        boolean bufferstep = false;
        int inputbuffer = 0;

        int numSamples = inLength * 2;
        int inIndex = inOffset;

        for (int i = 0; i < numSamples; i++) {
            int delta;
            if (bufferstep) {
                delta = inputbuffer & 0x0F;
                inIndex++;
            } else {
                inputbuffer = adpcmIn[inIndex] & 0xFF;
                delta = (inputbuffer >> 4) & 0x0F;
            }
            bufferstep = !bufferstep;

            int step = STEP_SIZE_TABLE[index];
            int vpdiff = step >> 3;
            if ((delta & 4) != 0) vpdiff += step;
            if ((delta & 2) != 0) vpdiff += (step >> 1);
            if ((delta & 1) != 0) vpdiff += (step >> 2);

            if ((delta & 8) != 0) {
                valprev -= vpdiff;
            } else {
                valprev += vpdiff;
            }

            if (valprev > 32767) valprev = 32767;
            else if (valprev < -32768) valprev = -32768;

            index += INDEX_TABLE[delta];
            if (index < 0) index = 0;
            else if (index > 88) index = 88;

            pcmOut[outIndex++] = (byte) (valprev & 0xFF);
            pcmOut[outIndex++] = (byte) ((valprev >> 8) & 0xFF);
        }

        state.valprev = valprev;
        state.index = index;
        return outIndex - outOffset;
    }
}
