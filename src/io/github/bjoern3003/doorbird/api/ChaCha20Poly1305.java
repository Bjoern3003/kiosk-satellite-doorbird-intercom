// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird.api;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/** DoorBird's original ChaCha20-Poly1305 construction with an 8-byte nonce, also on Android 8. */
public final class ChaCha20Poly1305 {
    private static final BigInteger POLY_PRIME = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));
    private static final BigInteger CLAMP = new BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16);
    private static final BigInteger TWO_128 = BigInteger.ONE.shiftLeft(128);

    private ChaCha20Poly1305() {}

    public static byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertextAndTag) throws GeneralSecurityException {
        if (key.length < 32 || nonce.length != 8 || ciphertextAndTag.length < 16) throw new GeneralSecurityException("Invalid DoorBird notification");
        byte[] key32 = Arrays.copyOf(key, 32);
        int ciphertextLength = ciphertextAndTag.length - 16;
        byte[] ciphertext = Arrays.copyOf(ciphertextAndTag, ciphertextLength);
        byte[] tag = Arrays.copyOfRange(ciphertextAndTag, ciphertextLength, ciphertextAndTag.length);
        byte[] expected = poly1305(macData(ciphertext), Arrays.copyOf(chaChaBlock(key32, 0, nonce), 32));
        if (!MessageDigest.isEqual(tag, expected)) throw new GeneralSecurityException("DoorBird notification authentication failed");
        byte[] plain = new byte[ciphertextLength];
        for (int offset = 0; offset < ciphertextLength; offset += 64) {
            byte[] stream = chaChaBlock(key32, 1 + offset / 64, nonce);
            int count = Math.min(64, ciphertextLength - offset);
            for (int i = 0; i < count; i++) plain[offset + i] = (byte) (ciphertext[offset + i] ^ stream[i]);
        }
        return plain;
    }

    private static byte[] macData(byte[] ciphertext) {
        byte[] data = new byte[ciphertext.length + 16];
        putLittleEndian64(data, 0, 0);
        System.arraycopy(ciphertext, 0, data, 8, ciphertext.length);
        putLittleEndian64(data, ciphertext.length + 8, ciphertext.length);
        return data;
    }

    private static byte[] poly1305(byte[] data, byte[] key) {
        BigInteger r = littleEndian(Arrays.copyOfRange(key, 0, 16)).and(CLAMP);
        BigInteger s = littleEndian(Arrays.copyOfRange(key, 16, 32));
        BigInteger accumulator = BigInteger.ZERO;
        for (int offset = 0; offset < data.length; offset += 16) {
            int count = Math.min(16, data.length - offset);
            byte[] block = Arrays.copyOfRange(data, offset, offset + count);
            BigInteger n = littleEndian(block).add(BigInteger.ONE.shiftLeft(8 * count));
            accumulator = accumulator.add(n).multiply(r).mod(POLY_PRIME);
        }
        byte[] result = new byte[16];
        byte[] value = accumulator.add(s).mod(TWO_128).toByteArray();
        for (int i = 0; i < value.length && i < 16; i++) result[i] = value[value.length - 1 - i];
        return result;
    }

    private static BigInteger littleEndian(byte[] bytes) {
        byte[] reversed = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) reversed[i] = bytes[bytes.length - 1 - i];
        return new BigInteger(1, reversed);
    }

    private static byte[] chaChaBlock(byte[] key, int counter, byte[] nonce) {
        int[] initial = {
            0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
            littleEndian32(key, 0), littleEndian32(key, 4), littleEndian32(key, 8), littleEndian32(key, 12),
            littleEndian32(key, 16), littleEndian32(key, 20), littleEndian32(key, 24), littleEndian32(key, 28),
            counter, 0, littleEndian32(nonce, 0), littleEndian32(nonce, 4)
        };
        int[] x = initial.clone();
        for (int round = 0; round < 10; round++) {
            quarter(x, 0, 4, 8, 12); quarter(x, 1, 5, 9, 13); quarter(x, 2, 6, 10, 14); quarter(x, 3, 7, 11, 15);
            quarter(x, 0, 5, 10, 15); quarter(x, 1, 6, 11, 12); quarter(x, 2, 7, 8, 13); quarter(x, 3, 4, 9, 14);
        }
        byte[] block = new byte[64];
        for (int i = 0; i < 16; i++) putLittleEndian32(block, i * 4, x[i] + initial[i]);
        return block;
    }

    private static void quarter(int[] x, int a, int b, int c, int d) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    private static int littleEndian32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8) | ((bytes[offset + 2] & 0xff) << 16) | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static void putLittleEndian32(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }

    private static void putLittleEndian64(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 8; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }
}
