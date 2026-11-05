package com.linbit.linstor.layer.drbd.utils;

import com.linbit.Platform;
import com.linbit.linstor.logging.ErrorReporter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BackingDeviceHoldTest
{
    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private static final int ROUNDS = 50;

    private final ErrorReporter errorReporter = Mockito.mock(ErrorReporter.class);

    private static long openFds() throws IOException
    {
        try (var fds = Files.list(Paths.get("/proc/self/fd")))
        {
            return fds.count();
        }
    }

    @Test
    public void opensExistingDevicesAndSkipsNullAndMissing() throws IOException
    {
        String dev = tmpDir.newFile("dev").getPath();
        String missing = tmpDir.getRoot().toPath().resolve("missing").toString();
        List<FileChannel> held;
        try (BackingDeviceHold hold = BackingDeviceHold.createEnabled(errorReporter))
        {
            hold.add(Arrays.asList(dev, null, missing));
            held = new ArrayList<>(hold.getChannels());
            assertEquals(1, held.size());
            assertTrue(held.get(0).isOpen());
        }
        assertFalse(held.get(0).isOpen());
    }

    @Test
    public void releasesOnException() throws IOException
    {
        String dev = tmpDir.newFile("dev").getPath();
        List<FileChannel> held = new ArrayList<>();
        try (BackingDeviceHold hold = BackingDeviceHold.createEnabled(errorReporter))
        {
            hold.add(Collections.singletonList(dev));
            held.addAll(hold.getChannels());
            throw new IllegalStateException("drbdmeta failed");
        }
        catch (IllegalStateException expected)
        {
            // expected
        }
        assertEquals(1, held.size());
        assertFalse(held.get(0).isOpen());
    }

    @Test
    public void noDescriptorLeak() throws IOException
    {
        Assume.assumeTrue(Platform.isLinux());
        String dev = tmpDir.newFile("dev").getPath();
        long before = openFds();
        for (int round = 0; round < ROUNDS; round++)
        {
            try (BackingDeviceHold hold = BackingDeviceHold.createEnabled(errorReporter))
            {
                hold.add(Arrays.asList(dev, dev));
            }
        }
        assertEquals(before, openFds());
    }

    @Test
    public void deviceRemovedWhileHeldClosesCleanly() throws IOException
    {
        Path dev = tmpDir.newFile("dev").toPath();
        try (BackingDeviceHold hold = BackingDeviceHold.createEnabled(errorReporter))
        {
            hold.add(Collections.singletonList(dev.toString()));
            Files.delete(dev);
            assertEquals(1, hold.getChannels().size());
        }
        Mockito.verify(errorReporter, Mockito.never()).reportError(Mockito.any(Throwable.class));
    }

    @Test
    public void disabledHoldOpensNothing() throws IOException
    {
        Assume.assumeFalse("true".equalsIgnoreCase(System.getenv(BackingDeviceHold.ENABLE_ENV)));
        String dev = tmpDir.newFile("dev").getPath();
        try (BackingDeviceHold hold = BackingDeviceHold.create(errorReporter))
        {
            hold.add(Collections.singletonList(dev));
            assertTrue(hold.getChannels().isEmpty());
        }
    }

    @Test
    public void unreadableDeviceIsSkipped() throws IOException
    {
        Assume.assumeTrue(Platform.isLinux());
        Path dev = tmpDir.newFile("dev").toPath();
        Assume.assumeTrue(dev.toFile().setReadable(false, false));
        Assume.assumeFalse("running as root", Files.isReadable(dev));
        try (BackingDeviceHold hold = BackingDeviceHold.createEnabled(errorReporter))
        {
            hold.add(Collections.singletonList(dev.toString()));
            assertTrue(hold.getChannels().isEmpty());
        }
        catch (RuntimeException exc)
        {
            fail("an unreadable device must be skipped, not thrown: " + exc);
        }
    }
}
