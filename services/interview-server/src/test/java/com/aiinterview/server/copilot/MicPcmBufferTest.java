package com.aiinterview.server.copilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MicPcmBufferTest {

    @Test
    void appendAndSnapshotWithinCapacity() {
        MicPcmBuffer buffer = new MicPcmBuffer(8);
        buffer.append(new byte[] {1, 2, 3});
        assertEquals(3, buffer.size());
        assertArrayEquals(new byte[] {1, 2, 3}, buffer.snapshot());
    }

    @Test
    void dropsOldestWhenOverCapacity() {
        MicPcmBuffer buffer = new MicPcmBuffer(4);
        buffer.append(new byte[] {1, 2, 3, 4, 5});
        assertEquals(4, buffer.size());
        assertArrayEquals(new byte[] {2, 3, 4, 5}, buffer.snapshot());
    }

    @Test
    void resetClearsBuffer() {
        MicPcmBuffer buffer = new MicPcmBuffer(8);
        buffer.append(new byte[] {9});
        buffer.reset();
        assertEquals(0, buffer.size());
        assertArrayEquals(new byte[0], buffer.snapshot());
    }

    @Test
    void wraparoundWritePreservesLogicalOrder() {
        MicPcmBuffer buffer = new MicPcmBuffer(8);
        buffer.append(new byte[] {1, 2, 3, 4, 5, 6});
        buffer.append(new byte[] {7, 8, 9, 10});
        assertEquals(8, buffer.size());
        assertArrayEquals(new byte[] {3, 4, 5, 6, 7, 8, 9, 10}, buffer.snapshot());
    }

    @Test
    void fullRingSteadyStateDropsExactlyOldest() {
        MicPcmBuffer buffer = new MicPcmBuffer(4);
        buffer.append(new byte[] {1, 2, 3, 4, 5, 6});
        buffer.append(new byte[] {7, 8});
        buffer.append(new byte[] {9});
        assertEquals(4, buffer.size());
        assertArrayEquals(new byte[] {6, 7, 8, 9}, buffer.snapshot());
    }

    @Test
    void appendLargerThanCapacityKeepsFrameTail() {
        MicPcmBuffer buffer = new MicPcmBuffer(4);
        buffer.append(new byte[] {1, 2});
        buffer.append(new byte[] {3, 4, 5, 6, 7, 8});
        assertEquals(4, buffer.size());
        assertArrayEquals(new byte[] {5, 6, 7, 8}, buffer.snapshot());
    }
}
