package com.linbit.linstor.layer.storage.utils;

import com.linbit.extproc.ExtCmd.OutputData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RetryIfDeviceBusyTest
{
    private static final OutputData BUSY = failed("cannot destroy 'tank/rsc_00000': dataset is busy\n");
    private static final OutputData OTHER = failed("cannot open 'tank/rsc_00000': dataset does not exist\n");

    private final List<Long> sleeps = new ArrayList<>();

    private static OutputData failed(String stdErr)
    {
        return new OutputData(
            new String[] {"zfs", "destroy", "tank/rsc_00000"},
            new byte[0],
            stdErr.getBytes(StandardCharsets.UTF_8),
            1
        );
    }

    private RetryIfDeviceBusy handler(int count, long delayInitial, long delayMax)
    {
        return new RetryIfDeviceBusy(count, delayInitial, delayMax, sleeps::add);
    }

    @Test
    public void backoffDoublesUpToMaxAndIsBounded()
    {
        RetryIfDeviceBusy retryHandler = handler(6, 200, 2_000);
        for (int idx = 0; idx < 6; idx++)
        {
            assertThat(retryHandler.retry(BUSY)).isTrue();
        }
        assertThat(retryHandler.retry(BUSY)).isFalse();
        assertThat(sleeps).containsExactly(200L, 400L, 800L, 1_600L, 2_000L, 2_000L);
    }

    @Test
    public void noRetryIfNotBusy()
    {
        RetryIfDeviceBusy retryHandler = handler(6, 200, 2_000);
        assertThat(retryHandler.retry(OTHER)).isFalse();
        assertThat(sleeps).isEmpty();
    }

    @Test
    public void defaultKeepsConstantDelay()
    {
        RetryIfDeviceBusy retryHandler = new RetryIfDeviceBusy(3, 100, 100, sleeps::add);
        assertThat(retryHandler.retry(BUSY)).isTrue();
        assertThat(retryHandler.retry(BUSY)).isTrue();
        assertThat(retryHandler.retry(BUSY)).isTrue();
        assertThat(retryHandler.retry(BUSY)).isFalse();
        assertThat(sleeps).containsExactly(100L, 100L, 100L);
    }

    @Test
    public void neverSkips()
    {
        assertThat(handler(6, 200, 2_000).skip(BUSY)).isFalse();
    }
}
