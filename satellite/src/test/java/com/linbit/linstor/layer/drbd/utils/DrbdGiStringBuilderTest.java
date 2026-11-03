package com.linbit.linstor.layer.drbd.utils;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class DrbdGiStringBuilderTest
{
    private static final String CURRENT = "3B0708FCA35F55B6";
    private static final String BITMAP = "620A54AC15B32A2B";
    private static final String YOUNGER = "F1A34DE7C3C77076";

    @Test
    public void emptyBuilder()
    {
        // three empty uuid slots followed by two unset flags
        assertThat(new DrbdGiStringBuilder().build()).isEqualTo(":::::");
    }

    @Test
    public void uuidsWithUpToDateData()
    {
        String giString = new DrbdGiStringBuilder()
            .withCurrentUUID(CURRENT)
            .withBitmapBaseDataUUID(BITMAP)
            .withYoungerUUID(YOUNGER)
            .withConsistent(true)
            .withUpToDate(true)
            .build();

        assertThat(giString).isEqualTo(CURRENT + ":" + BITMAP + ":" + YOUNGER + "::1:1");
    }

    @Test
    public void upToDateImpliesConsistencyFlag()
    {
        // the consistency flag is set if the data is consistent OR up-to-date
        String giString = new DrbdGiStringBuilder()
            .withCurrentUUID(CURRENT)
            .withUpToDate(true)
            .build();

        assertThat(giString).isEqualTo(CURRENT + "::::1:1");
    }

    @Test
    public void consistentButNotUpToDate()
    {
        String giString = new DrbdGiStringBuilder()
            .withCurrentUUID(CURRENT)
            .withConsistent(true)
            .build();

        assertThat(giString).isEqualTo(CURRENT + "::::1:");
    }

    @Test
    public void peerDiskWasOutdatedAppendsSkippedFlagSlots()
    {
        String giString = new DrbdGiStringBuilder()
            .withCurrentUUID(CURRENT)
            .withConsistent(true)
            .withUpToDate(true)
            .withPeerDiskWasOutdateOrInconsistent(true)
            .build();

        assertThat(giString).isEqualTo(CURRENT + "::::1:1:::::::1");
    }

    @Test
    public void getters()
    {
        DrbdGiStringBuilder builder = new DrbdGiStringBuilder()
            .withCurrentUUID(CURRENT)
            .withUpToDate(true);

        assertThat(builder.getCurrentUuid()).isEqualTo(CURRENT);
        assertThat(builder.isUpToDate()).isTrue();
        assertThat(new DrbdGiStringBuilder().getCurrentUuid()).isNull();
        assertThat(new DrbdGiStringBuilder().isUpToDate()).isFalse();
    }

    @Test
    public void currentUuidOnlyHasNoPeerSlotData()
    {
        // the GI DrbdLayer uses for the other node-ids when skipping the initial sync
        assertThat(new DrbdGiStringBuilder().withCurrentUUID(CURRENT).hasPeerSlotData()).isFalse();
        assertThat(new DrbdGiStringBuilder().hasPeerSlotData()).isFalse();
        assertThat(
            new DrbdGiStringBuilder()
                .withCurrentUUID(CURRENT)
                .withYoungerUUID(YOUNGER)
                .withConsistent(true)
                .withUpToDate(true)
                .hasPeerSlotData()
        ).isFalse();
    }

    @Test
    public void bitmapUuidIsPeerSlotData()
    {
        // the GI DrbdLayer uses for the other node-ids when the local satellite won the UpToDate race
        assertThat(new DrbdGiStringBuilder().withBitmapBaseDataUUID(BITMAP).hasPeerSlotData()).isTrue();
    }

    @Test
    public void peerOutdatedFlagIsPeerSlotData()
    {
        assertThat(new DrbdGiStringBuilder().withPeerDiskWasOutdateOrInconsistent(true).hasPeerSlotData()).isTrue();
    }
}
