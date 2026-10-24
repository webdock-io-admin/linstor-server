package com.linbit.linstor.layer.storage.utils;

import com.linbit.extproc.ExtCmd.OutputData;
import com.linbit.linstor.storage.utils.Commands;

import java.nio.charset.StandardCharsets;

public class RetryIfDeviceBusy implements Commands.RetryHandler
{
    private static final int DEFAULT_RETRY_DELAY_IN_MS = 100;
    private static final int DEFAULT_RETRY_COUNT = 3;
    private final int countMax;
    private final long delayMaxInMs;
    private final Sleeper sleeper;
    private int countCurrent;
    private long delayNextInMs;

    @FunctionalInterface
    public interface Sleeper
    {
        void sleep(long millis) throws InterruptedException;
    }

    public RetryIfDeviceBusy()
    {
        this(DEFAULT_RETRY_COUNT);
    }

    public RetryIfDeviceBusy(int countRef)
    {
        this(countRef, DEFAULT_RETRY_DELAY_IN_MS, DEFAULT_RETRY_DELAY_IN_MS);
    }

    /**
     * Retries up to <code>countRef</code> times while the command's stderr contains "busy". The delay before the
     * first retry is <code>delayInitialInMsRef</code> and doubles with each further retry, but never exceeds
     * <code>delayMaxInMsRef</code>.
     */
    public RetryIfDeviceBusy(int countRef, long delayInitialInMsRef, long delayMaxInMsRef)
    {
        this(countRef, delayInitialInMsRef, delayMaxInMsRef, Thread::sleep);
    }

    public RetryIfDeviceBusy(int countRef, long delayInitialInMsRef, long delayMaxInMsRef, Sleeper sleeperRef)
    {
        countMax = countRef;
        delayMaxInMs = delayMaxInMsRef;
        sleeper = sleeperRef;
        countCurrent = 0;
        delayNextInMs = Math.min(delayInitialInMsRef, delayMaxInMsRef);
    }

    @Override
    public boolean retry(OutputData outputData)
    {
        boolean retry = countCurrent++ < countMax;
        if (retry)
        {
            String stdErr = new String(outputData.stderrData, StandardCharsets.UTF_8);
            if (!stdErr.contains("busy"))
            {
                retry = false;
            }
        }
        if (retry)
        {
            try
            {
                sleeper.sleep(delayNextInMs);
            }
            catch (InterruptedException ignored)
            {
            }
            delayNextInMs = Math.min(delayNextInMs * 2, delayMaxInMs);
        }
        return retry;
    }

    @Override
    public boolean skip(OutputData outData)
    {
        return false;
    }
}
