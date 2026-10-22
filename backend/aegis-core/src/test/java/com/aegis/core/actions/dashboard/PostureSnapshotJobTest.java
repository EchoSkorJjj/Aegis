package com.aegis.core.actions.dashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.aegis.core.platform.identity.IdentityInternalApi;
import com.aegis.core.platform.scheduling.SchedulerLease;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PostureSnapshotJobTest {
    private final RiskPosture posture = mock(RiskPosture.class);
    private final IdentityInternalApi identity = mock(IdentityInternalApi.class);
    private final SchedulerLease lease = mock(SchedulerLease.class);
    private final CurrentOrg currentOrg = new CurrentOrg();
    private final PostureSnapshotJob job = new PostureSnapshotJob(posture, identity, currentOrg, lease);

    @Test
    void anInstanceWithoutTheLeaseDoesNoWork() {
        job.run();
        verifyNoInteractions(posture, identity);
    }

    @Test
    void eachOrganizationIsScopedAndOneFailureDoesNotStopTheNext() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(lease.held()).thenReturn(true);
        when(identity.organizationIds()).thenReturn(List.of(first, second));
        List<UUID> captured = new ArrayList<>();
        doAnswer(call -> {
            captured.add(currentOrg.require());
            if (currentOrg.require().equals(first)) {
                throw new IllegalStateException("Unavailable");
            }
            return null;
        }).when(posture).capture();
        job.run();
        assertThat(captured).containsExactly(first, second);
        assertThat(currentOrg.orgId()).isEmpty();
    }
}
