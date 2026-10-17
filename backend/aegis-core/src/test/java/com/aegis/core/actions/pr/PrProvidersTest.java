package com.aegis.core.actions.pr;

import java.util.ArrayList;
import java.util.List;

import com.aegis.core.platform.common.ApiExceptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrProvidersTest {

    private static final RepositoryRef REPOSITORY = new RepositoryRef("GitHub.COM", "acme/payments");

    @Test
    void selectsSupportingProviderWithoutPerformingAnAction() {
        RecordingPrProvider github = new RecordingPrProvider("github.com");
        RecordingPrProvider gitlab = new RecordingPrProvider("gitlab.com");
        var registry = new PrProviders(List.of(gitlab, github));
        assertThat(registry.forRepository(REPOSITORY)).isSameAs(github);
        assertThat(github.received).isEmpty();
        var draft = new PullRequestDraft(REPOSITORY, "develop", "aegis/upgrade", "Upgrade",
                "For review", "Upgrade dependency", List.of(new FileChange("package.json", "{}")));
        PullRequestRef ref = registry.forRepository(REPOSITORY).openDraftPullRequest(draft);
        assertThat(ref.provider()).isEqualTo("recording");
        assertThat(ref.headBranch()).isEqualTo("aegis/upgrade");
        assertThat(github.received).containsExactly(draft);
        assertThat(gitlab.received).isEmpty();
    }

    @Test
    void absentAndUnsupportedProvidersGiveClearConflict() {
        for (var registry : List.of(new PrProviders(List.of()),
                new PrProviders(List.of(new RecordingPrProvider("gitlab.com"))))) {
            assertThatThrownBy(() -> registry.forRepository(REPOSITORY))
                    .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("github.com");
        }
    }

    @Test
    void supportsSaysUpFrontWhetherAPullRequestCanBeOpened() {
        assertThat(new PrProviders(List.of(new RecordingPrProvider("github.com"))).supports(REPOSITORY)).isTrue();
        assertThat(new PrProviders(List.of()).supports(REPOSITORY)).isFalse();
        assertThat(new PrProviders(List.of(new RecordingPrProvider("gitlab.com"))).supports(REPOSITORY)).isFalse();
        assertThat(new PrProviders(List.of(new RecordingPrProvider("github.com"), new RecordingPrProvider("github.com")))
                .supports(REPOSITORY)).as("forRepository would refuse two").isFalse();
        assertThat(new PrProviders(List.of(new RecordingPrProvider("github.com"))).supports(null)).isFalse();
    }

    @Test
    void ambiguousProvidersAreRefusedAndRegistryDefensivelyCopiesInput() {
        var first = new RecordingPrProvider("github.com");
        var second = new RecordingPrProvider("github.com");
        var adapters = new ArrayList<PrProvider>(List.of(first));
        var registry = new PrProviders(adapters);
        adapters.add(second);
        assertThat(registry.forRepository(REPOSITORY)).isSameAs(first);
        assertThatThrownBy(() -> new PrProviders(adapters).forRepository(REPOSITORY))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("Multiple");
    }
}
