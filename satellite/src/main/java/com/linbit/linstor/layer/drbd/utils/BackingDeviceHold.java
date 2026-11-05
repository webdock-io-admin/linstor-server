package com.linbit.linstor.layer.drbd.utils;

import com.linbit.Platform;
import com.linbit.linstor.annotation.Nullable;
import com.linbit.linstor.logging.ErrorReporter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Keeps backing devices open (read-only, not exclusive) while a series of drbdmeta / drbdadm calls runs on them.
 * <p>
 * Closing the last open file descriptor of a ZFS zvol waits for a txg sync. Each drbdmeta call opens and closes
 * the backing device, so on a loaded pool every call paid that wait (measured: ~4.5 s per close, 32 set-gi calls per
 * new volume). While this hold has the device open, those closes are not the last one and return at once; only the
 * close of this hold may wait, once. After "drbdsetup attach" the kernel holds the device, so even that close is
 * cheap.
 * </p>
 * <p>
 * The descriptors are opened without O_EXCL, so they do not conflict with drbdmeta's or the kernel's exclusive
 * claim. Devices that do not exist (yet) or cannot be opened are skipped; the calls then behave as without the
 * hold. Off by default; enabled with the environment variable {@value #ENABLE_ENV} = "true" on the satellite.
 * </p>
 */
public final class BackingDeviceHold implements AutoCloseable
{
    public static final String ENABLE_ENV = "LINSTOR_SATELLITE_HOLD_BACKING_DEVICES";

    private final List<FileChannel> channels = new ArrayList<>();
    private final ErrorReporter errorReporter;
    private final boolean enabled;

    private BackingDeviceHold(ErrorReporter errorReporterRef, boolean enabledRef)
    {
        errorReporter = errorReporterRef;
        enabled = enabledRef;
    }

    public static boolean isEnabled()
    {
        return Platform.isLinux() && "true".equalsIgnoreCase(System.getenv(ENABLE_ENV));
    }

    /**
     * Returns an empty hold; {@link #add(Collection)} opens devices only if the hold is enabled.
     */
    public static BackingDeviceHold create(ErrorReporter errorReporterRef)
    {
        return new BackingDeviceHold(errorReporterRef, isEnabled());
    }

    // package-private for tests: a hold that opens regardless of the enable switch
    static BackingDeviceHold createEnabled(ErrorReporter errorReporterRef)
    {
        return new BackingDeviceHold(errorReporterRef, true);
    }

    /**
     * Opens the given device paths and keeps them open until {@link #close()}; null entries, missing devices and
     * devices that cannot be opened are skipped.
     */
    public void add(Collection<@Nullable String> devicePaths)
    {
        if (enabled)
        {
            for (@Nullable String devicePath : devicePaths)
            {
                if (devicePath != null)
                {
                    Path path = Paths.get(devicePath);
                    if (Files.exists(path))
                    {
                        try
                        {
                            channels.add(FileChannel.open(path, StandardOpenOption.READ));
                        }
                        catch (IOException | RuntimeException exc)
                        {
                            errorReporter.logInfo("Not holding %s open: %s", devicePath, exc.getMessage());
                        }
                    }
                }
            }
        }
    }

    // package-private for tests
    List<FileChannel> getChannels()
    {
        return channels;
    }

    @Override
    public void close()
    {
        for (FileChannel channel : channels)
        {
            try
            {
                channel.close();
            }
            catch (IOException exc)
            {
                errorReporter.logDebug("Closing a held backing device failed: %s", exc.getMessage());
            }
        }
        channels.clear();
    }
}
