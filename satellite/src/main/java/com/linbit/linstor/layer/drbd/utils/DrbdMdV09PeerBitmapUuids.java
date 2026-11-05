package com.linbit.linstor.layer.drbd.utils;

import com.linbit.Platform;
import com.linbit.linstor.core.types.NodeId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes the bitmap UUID of all peer node-ids into a DRBD 9 (v09) meta data superblock with a single write.
 * <p>
 * This replaces one "drbdmeta set-gi" per node-id with a GI that only sets the bitmap UUID (":&lt;uuid&gt;::::").
 * Each drbdmeta call is a synchronous read-modify-write of the same 4 KiB superblock, which under storage load
 * costs a full sync per node-id. The offsets follow struct meta_data_on_disk_9 and struct peer_dev_md_on_disk_9
 * (drbd-headers drbd_meta_data.h); all fields are big endian. Only {@code peers[n].bitmap_uuid} is changed, which is
 * exactly what such a set-gi changes.
 * </p>
 * <p>
 * Off by default; enabled with the environment variable {@value #ENABLE_ENV} = "true" on the satellite.
 * </p>
 */
public final class DrbdMdV09PeerBitmapUuids
{
    public static final String ENABLE_ENV = "LINSTOR_SATELLITE_SETGI_SINGLE_WRITE";

    static final int SUPERBLOCK_SIZE = 4096;
    static final int MAGIC_OFFSET = 60;
    // DRBD_MD_MAGIC_09 = DRBD_MAGIC + 6 = 0x83740267 + 6 (drbd-headers uapi/linux/drbd.h)
    static final int DRBD_MD_MAGIC_09 = 0x8374026D;
    static final int PEERS_OFFSET = 112;
    static final int PEER_SIZE = 32;
    static final int PEER_BITMAP_UUID_OFFSET = 0;
    // DRBD_PEERS_MAX (= DRBD_NODE_ID_MAX) entries in peers[], the same range as the set-gi loop in DrbdLayer
    static final int NODE_ID_COUNT = NodeId.NODE_ID_MAX + 1;
    private static final long ALIGN_4K_MASK = ~4095L;
    private static final int HEX_RADIX = 16;

    private DrbdMdV09PeerBitmapUuids()
    {
    }

    public static boolean isEnabled()
    {
        return Platform.isLinux() && "true".equalsIgnoreCase(System.getenv(ENABLE_ENV));
    }

    /**
     * Sets {@code peers[n].bitmap_uuid} to the given UUID for every node-id n except {@code skipNodeId}, in the
     * superblock of the given meta data device, and writes it back synchronously.
     *
     * @param mdDevice the device that holds the meta data (the data device for internal meta data)
     * @param externalMd true for flex-external meta data (superblock at offset 0), false for internal meta data
     *     (superblock in the last 4 KiB aligned block)
     * @param skipNodeId the node-id to leave unchanged (the local node, whose GI is set separately)
     * @param bitmapUuidHex the bitmap UUID as drbdmeta takes it: hexadecimal, without prefix
     */
    public static void write(Path mdDevice, boolean externalMd, int skipNodeId, String bitmapUuidHex)
        throws IOException
    {
        final long bitmapUuid;
        try
        {
            bitmapUuid = Long.parseUnsignedLong(bitmapUuidHex, HEX_RADIX);
        }
        catch (NumberFormatException exc)
        {
            throw new IOException("Not a hexadecimal UUID: " + bitmapUuidHex, exc);
        }
        try (
            FileChannel channel = FileChannel.open(
                mdDevice,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC
            )
        )
        {
            final long offset = externalMd ? 0 : superblockOffset(channel.size());
            ByteBuffer superblock = ByteBuffer.allocate(SUPERBLOCK_SIZE).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, superblock, offset);
            setPeerBitmapUuids(superblock, skipNodeId, bitmapUuid);
            superblock.rewind();
            writeFully(channel, superblock, offset);
            channel.force(false);
        }
    }

    static long superblockOffset(long deviceSize) throws IOException
    {
        long aligned = deviceSize & ALIGN_4K_MASK;
        if (aligned < SUPERBLOCK_SIZE)
        {
            throw new IOException("Device of " + deviceSize + " bytes is too small for DRBD meta data");
        }
        return aligned - SUPERBLOCK_SIZE;
    }

    static void setPeerBitmapUuids(ByteBuffer superblock, int skipNodeId, long bitmapUuid) throws IOException
    {
        int magic = superblock.getInt(MAGIC_OFFSET);
        if (magic != DRBD_MD_MAGIC_09)
        {
            throw new IOException(String.format("No DRBD 9 meta data (magic 0x%08X)", magic));
        }
        for (int nodeId = 0; nodeId < NODE_ID_COUNT; nodeId++)
        {
            if (nodeId != skipNodeId)
            {
                superblock.putLong(PEERS_OFFSET + nodeId * PEER_SIZE + PEER_BITMAP_UUID_OFFSET, bitmapUuid);
            }
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException
    {
        long pos = offset;
        while (buffer.hasRemaining())
        {
            int read = channel.read(buffer, pos);
            if (read < 0)
            {
                throw new IOException("Unexpected end of device while reading the DRBD meta data superblock");
            }
            pos += read;
        }
        buffer.flip();
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException
    {
        long pos = offset;
        while (buffer.hasRemaining())
        {
            pos += channel.write(buffer, pos);
        }
    }
}
