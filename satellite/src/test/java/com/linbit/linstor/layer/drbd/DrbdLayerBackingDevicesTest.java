package com.linbit.linstor.layer.drbd;

import com.linbit.ValueOutOfRangeException;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.storage.data.adapter.drbd.DrbdRscData;
import com.linbit.linstor.storage.data.adapter.drbd.DrbdVlmData;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;

public class DrbdLayerBackingDevicesTest
{
    @SuppressWarnings("unchecked")
    private static DrbdVlmData<Resource> vlm(String dataDevice, String metaDiskPath, boolean hasDisk)
    {
        DrbdVlmData<Resource> vlmData = Mockito.mock(DrbdVlmData.class);
        Mockito.when(vlmData.getDataDevice()).thenReturn(dataDevice);
        Mockito.when(vlmData.getMetaDiskPath()).thenReturn(metaDiskPath);
        Mockito.when(vlmData.hasDisk()).thenReturn(hasDisk);
        return vlmData;
    }

    @SuppressWarnings("unchecked")
    private static DrbdRscData<Resource> rsc(List<DrbdVlmData<Resource>> vlms) throws ValueOutOfRangeException
    {
        Map<VolumeNumber, DrbdVlmData<Resource>> vlmMap = new LinkedHashMap<>();
        for (int idx = 0; idx < vlms.size(); idx++)
        {
            vlmMap.put(new VolumeNumber(idx), vlms.get(idx));
        }
        DrbdRscData<Resource> rscData = Mockito.mock(DrbdRscData.class);
        Mockito.when(rscData.getVlmLayerObjects()).thenReturn(vlmMap);
        return rscData;
    }

    /**
     * A new or cloned volume is not attached yet (hasDisk() false) while its metadata is checked and created and
     * drbdadm attaches it; its backing devices must be held exactly then.
     */
    @Test
    public void notYetAttachedVolumesAreHeld() throws ValueOutOfRangeException
    {
        DrbdRscData<Resource> rscData = rsc(
            Arrays.asList(
                vlm("/dev/zvol/tank/rsc_00000", null, false),
                vlm("/dev/zvol/tank/rsc_00001", "/dev/zvol/tank/rsc.meta_00001", false)
            )
        );
        assertEquals(
            Arrays.asList(
                "/dev/zvol/tank/rsc_00000", null,
                "/dev/zvol/tank/rsc_00001", "/dev/zvol/tank/rsc.meta_00001"
            ),
            DrbdLayer.backingDevices(rscData)
        );
    }

    @Test
    public void attachedVolumesAreHeld() throws ValueOutOfRangeException
    {
        DrbdRscData<Resource> rscData = rsc(Arrays.asList(vlm("/dev/zvol/tank/rsc_00000", null, true)));
        assertEquals(Arrays.asList("/dev/zvol/tank/rsc_00000", null), DrbdLayer.backingDevices(rscData));
    }

    @Test
    public void volumeWithoutBackingDeviceYieldsOnlyNulls() throws ValueOutOfRangeException
    {
        DrbdRscData<Resource> rscData = rsc(Arrays.asList(vlm(null, null, false)));
        assertEquals(Arrays.asList(null, null), DrbdLayer.backingDevices(rscData));
    }
}
