package com.linbit.linstor.layer.drbd.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class DrbdMdV09PeerBitmapUuidsTest
{
    private static final long UUID = 0x1234_5678_9ABC_DEF0L;
    private static final int LOCAL_NODE_ID = 3;
    private static final byte FILL = (byte) 0x5A;
    private static final int SB = DrbdMdV09PeerBitmapUuids.SUPERBLOCK_SIZE;
    private static final int LAST_NODE_ID = DrbdMdV09PeerBitmapUuids.NODE_ID_COUNT - 1;

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private static byte[] superblock()
    {
        byte[] data = new byte[DrbdMdV09PeerBitmapUuids.SUPERBLOCK_SIZE];
        Arrays.fill(data, FILL);
        ByteBuffer.wrap(data).putInt(DrbdMdV09PeerBitmapUuids.MAGIC_OFFSET, DrbdMdV09PeerBitmapUuids.DRBD_MD_MAGIC_09);
        return data;
    }

    private static long bitmapUuid(byte[] data, int nodeId)
    {
        return ByteBuffer.wrap(data).getLong(
            DrbdMdV09PeerBitmapUuids.PEERS_OFFSET + nodeId * DrbdMdV09PeerBitmapUuids.PEER_SIZE
        );
    }

    @Test
    public void setsEveryOtherNodeIdAndNothingElse() throws IOException
    {
        byte[] before = superblock();
        byte[] after = before.clone();
        DrbdMdV09PeerBitmapUuids.setPeerBitmapUuids(ByteBuffer.wrap(after), LOCAL_NODE_ID, UUID);

        for (int nodeId = 0; nodeId < DrbdMdV09PeerBitmapUuids.NODE_ID_COUNT; nodeId++)
        {
            assertEquals(
                "node-id " + nodeId,
                nodeId == LOCAL_NODE_ID ? bitmapUuid(before, nodeId) : UUID,
                bitmapUuid(after, nodeId)
            );
        }
        // every byte outside the 31 bitmap_uuid fields is untouched
        byte[] masked = after.clone();
        for (int nodeId = 0; nodeId < DrbdMdV09PeerBitmapUuids.NODE_ID_COUNT; nodeId++)
        {
            if (nodeId != LOCAL_NODE_ID)
            {
                int off = DrbdMdV09PeerBitmapUuids.PEERS_OFFSET + nodeId * DrbdMdV09PeerBitmapUuids.PEER_SIZE;
                Arrays.fill(masked, off, off + Long.BYTES, FILL);
            }
        }
        assertArrayEquals(before, masked);
    }

    @Test
    public void refusesWithoutDrbd9Magic()
    {
        byte[] data = superblock();
        ByteBuffer.wrap(data).putInt(DrbdMdV09PeerBitmapUuids.MAGIC_OFFSET, 0);
        try
        {
            DrbdMdV09PeerBitmapUuids.setPeerBitmapUuids(ByteBuffer.wrap(data), LOCAL_NODE_ID, UUID);
            fail("must refuse a superblock without the v09 magic");
        }
        catch (IOException expected)
        {
            // expected
        }
    }

    @Test
    public void internalSuperblockIsTheLastAligned4k() throws IOException
    {
        assertEquals(SB, DrbdMdV09PeerBitmapUuids.superblockOffset(2 * SB));
        assertEquals(SB, DrbdMdV09PeerBitmapUuids.superblockOffset(2 * SB + 100));
    }

    @Test
    public void writesInternalAndExternalSuperblocks() throws IOException
    {
        Path internal = tmpDir.newFile("internal.img").toPath();
        final int devBlocks = 3;
        byte[] dev = new byte[devBlocks * SB];
        System.arraycopy(superblock(), 0, dev, (devBlocks - 1) * SB, SB);
        Files.write(internal, dev);
        DrbdMdV09PeerBitmapUuids.write(internal, false, LOCAL_NODE_ID, Long.toHexString(UUID).toUpperCase());
        byte[] written = Files.readAllBytes(internal);
        byte[] sb = Arrays.copyOfRange(written, (devBlocks - 1) * SB, written.length);
        assertEquals(UUID, bitmapUuid(sb, 0));
        assertArrayEquals(Arrays.copyOf(dev, (devBlocks - 1) * SB), Arrays.copyOf(written, (devBlocks - 1) * SB));

        Path external = tmpDir.newFile("external.img").toPath();
        Files.write(external, superblock());
        DrbdMdV09PeerBitmapUuids.write(external, true, LOCAL_NODE_ID, Long.toHexString(UUID));
        assertEquals(UUID, bitmapUuid(Files.readAllBytes(external), LAST_NODE_ID));
    }

    @Test
    public void onlyAPureBitmapUuidGiQualifies()
    {
        assertEquals("ABC", new DrbdGiStringBuilder().withBitmapBaseDataUUID("ABC").getBitmapUuidIfOnlyField());
        assertNull(
            new DrbdGiStringBuilder().withBitmapBaseDataUUID("ABC").withCurrentUUID("1").getBitmapUuidIfOnlyField()
        );
        assertNull(
            new DrbdGiStringBuilder().withBitmapBaseDataUUID("ABC").withPeerDiskWasOutdateOrInconsistent(true)
                .getBitmapUuidIfOnlyField()
        );
        assertNull(new DrbdGiStringBuilder().withCurrentUUID("1").getBitmapUuidIfOnlyField());
    }
}
