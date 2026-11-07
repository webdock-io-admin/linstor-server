package com.linbit.linstor.layer.storage.zfs;

import com.linbit.linstor.core.devmgr.StltReadOnlyInfo.ReadOnlyStorPool;
import com.linbit.linstor.core.devmgr.StltReadOnlyInfo.ReadOnlyVlmProviderInfo;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.layer.storage.zfs.utils.ZfsUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class ZfsProviderFetchAllocatedSizesTest
{
    /**
     * The allocated-size query lists exactly the queried volumes (ZfsUtils.listDatasets), never the recursive list
     * that also walks every snapshot of them. The provider is a mock calling the real method, ZfsUtils is mocked
     * statically: an unstubbed getZfsList would return null and fail the test.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void allocatedSizesUseTheNonRecursiveList() throws Exception
    {
        ZfsProvider provider = Mockito.mock(ZfsProvider.class, Mockito.CALLS_REAL_METHODS);
        doReturn("tank").when(provider).getZPool(any());

        ReadOnlyVlmProviderInfo roVlm = Mockito.mock(ReadOnlyVlmProviderInfo.class);
        when(roVlm.getReadOnlyStorPool()).thenReturn(Mockito.mock(ReadOnlyStorPool.class));
        when(roVlm.getIdentifier()).thenReturn("rsc_00000");
        when(roVlm.getResourceName()).thenReturn(new ResourceName("rsc"));
        when(roVlm.getRscSuffix()).thenReturn("");
        when(roVlm.getVlmNr()).thenReturn(new VolumeNumber(0));
        when(roVlm.getOrigAllocatedSize()).thenReturn(7L);

        try (MockedStatic<ZfsUtils> zfsUtils = mockStatic(ZfsUtils.class))
        {
            zfsUtils.when(() -> ZfsUtils.listDatasets(any(), any(), any())).thenReturn(new HashMap<>());

            Map<ReadOnlyVlmProviderInfo, Long> sizes = provider.fetchAllocatedSizes(Collections.singletonList(roVlm));

            ArgumentCaptor<Collection<String>> datasets = ArgumentCaptor.forClass(Collection.class);
            zfsUtils.verify(() -> ZfsUtils.listDatasets(any(), datasets.capture(), any()));
            assertThat(datasets.getValue()).containsExactly("tank/rsc_00000");
            zfsUtils.verifyNoMoreInteractions();
            // not listed (e.g. vanished): the previous allocated size is kept, as before
            assertThat(sizes).containsEntry(roVlm, 7L);
        }
    }
}
