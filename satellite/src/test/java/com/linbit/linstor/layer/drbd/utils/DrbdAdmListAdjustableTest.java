package com.linbit.linstor.layer.drbd.utils;

import com.linbit.Platform;
import com.linbit.drbd.DrbdVersion;
import com.linbit.extproc.ExtCmdFactory;
import com.linbit.linstor.core.StltConfigAccessor;
import com.linbit.linstor.layer.drbd.drbdstate.DrbdEventService;
import com.linbit.linstor.logging.ErrorReporter;

import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DrbdAdmListAdjustableTest
{
    private static final String RSC_WITHOUT_RES_FILE = "fix21-no-such-resource";

    @Test
    public void noResFileMeansNothingToAskDrbdadm() throws Exception
    {
        Assume.assumeTrue(Platform.isLinux());
        Assume.assumeTrue(!Files.exists(DrbdAdmScopedConf.resFile(RSC_WITHOUT_RES_FILE)));

        ExtCmdFactory extCmdFactory = Mockito.mock(ExtCmdFactory.class);
        DrbdAdm drbdAdm = new DrbdAdm(
            Mockito.mock(ErrorReporter.class),
            extCmdFactory,
            Mockito.mock(StltConfigAccessor.class),
            Mockito.mock(DrbdEventService.class),
            Mockito.mock(DrbdVersion.class)
        )
        {
            @Override
            boolean canScope()
            {
                return true;
            }
        };

        List<String> adjustable = drbdAdm.listAdjustable(Collections.singletonList(RSC_WITHOUT_RES_FILE));

        assertNotNull(adjustable);
        assertTrue(adjustable.isEmpty());
        // the caller appends to the list
        adjustable.add("other");
        Mockito.verifyNoInteractions(extCmdFactory);
    }
}
