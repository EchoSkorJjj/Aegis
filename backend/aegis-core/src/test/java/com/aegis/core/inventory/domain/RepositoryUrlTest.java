package com.aegis.core.inventory.domain;

import java.util.List;
import java.util.UUID;

import com.aegis.core.platform.common.ApiExceptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryUrlTest {

    private static final List<String> HOSTS = List.of("github.com", "gitlab.com", "bitbucket.org", "git.example.com");

    @Test
    void canonicalizesHostSuffixAndTrailingSlashWhilePreservingPathCase() {
        RepositoryUrl url = RepositoryUrl.parse(" HTTPS://GitHub.COM/Acme/Payments.git/ ", HOSTS);
        assertThat(url.host()).isEqualTo("github.com");
        assertThat(url.path()).isEqualTo("Acme/Payments");
        assertThat(url.canonical()).isEqualTo("https://github.com/Acme/Payments");
        assertThat(RepositoryUrl.parse(url.canonical(), HOSTS).canonical()).isEqualTo(url.canonical());
        assertThat(RepositoryUrl.parse("https://gitlab.com/group/sub/repo.git", HOSTS).path())
                .isEqualTo("group/sub/repo");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        " ", "not a URL", "http://github.com/a/b", "git://github.com/a/b.git", "git@evil.example:a/b.git",
        "ssh://git:secret@github.com/a/b.git", "https://evil.example/a/b", "https://github.com.evil.example/a/b",
        "https://github.com/owner", "https://github.com/a/../b", "https://github.com/a/%2e%2e/b",
        "https://github.com/a/repo%2fname", "https://github.com/a/-repo", "https://github.com/a/.git",
        "https://user:secret@github.com/a/b", "https://github.com:8443/a/b",
        "https://github.com/a/b/tree/../../evil/c", "https://gitlab.com/g/r/-/tree/%2e%2e/x", "https://gitlab.com/-/ide/x"
    })
    void refusesUnsupportedOrAmbiguousLocations(String raw) {
        assertThatThrownBy(() -> RepositoryUrl.parse(raw, HOSTS)).isInstanceOf(ApiExceptions.BadRequest.class);
    }

    /** What a browser's address bar adds is ignored, as the SBOM importer always did — never stored. */
    @ParameterizedTest
    @ValueSource(strings = {
        "https://github.com/a/b?tab=readme-ov-file", "https://github.com/a/b#readme",
        "https://github.com/a/b?token=secret", "https://github.com/a//b", "https://github.com/a/b//"
    })
    void dropsQueryFragmentAndEmptySegments(String raw) {
        assertThat(RepositoryUrl.parse(raw, HOSTS).canonical()).isEqualTo("https://github.com/a/b");
    }

    /** What people paste — an SSH clone URL, a clone URL with their username, a link into a branch or file. */
    @ParameterizedTest
    @CsvSource({
        "git@github.com:Acme/Payments.git,                         https://github.com/Acme/Payments",
        "ssh://git@gitlab.com/group/sub/repo.git,                  https://gitlab.com/group/sub/repo",
        "ssh://git@gitlab.com:2222/group/repo.git,                 https://gitlab.com/group/repo",
        "https://jdoe@bitbucket.org/team/repo.git,                 https://bitbucket.org/team/repo",
        "https://github.com/a/b/tree/feature/login,                https://github.com/a/b",
        "https://github.com/a/b/blob/main/pom.xml,                 https://github.com/a/b",
        "https://bitbucket.org/team/repo/src/main/,                https://bitbucket.org/team/repo",
        "https://gitlab.com/group/sub/repo/-/tree/main,            https://gitlab.com/group/sub/repo",
        "https://gitlab.com/group/repo/-/blob/main/pom.xml?ref_type=heads, https://gitlab.com/group/repo",
        "https://git.example.com/team/sub/repo,                    https://git.example.com/team/sub/repo"
    })
    void reducesWhatPeoplePasteToTheRepository(String raw, String canonical) {
        assertThat(RepositoryUrl.parse(raw, HOSTS).canonical()).isEqualTo(canonical);
    }

    @Test
    void respectsCustomHostListAndLengthLimit() {
        assertThat(RepositoryUrl.parse("https://git.example.com/a/b", List.of("GIT.EXAMPLE.COM")).host())
                .isEqualTo("git.example.com");
        assertThatThrownBy(() -> RepositoryUrl.parse("https://github.com/a/b", List.of()))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> RepositoryUrl.parse("https://github.com/a/" + "b".repeat(401), HOSTS))
                .isInstanceOf(ApiExceptions.BadRequest.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--upload-pack=bad", "a..b", "a//b", "a/", "a.", "a/.hidden", "a.lock", "a/b.lock", "a@{b", "a b"})
    void refusesUnsafeRefs(String ref) {
        assertThatThrownBy(() -> RepositoryUrl.normalizeRef(ref))
                .isInstanceOf(ApiExceptions.BadRequest.class).hasMessageContaining("branch or tag");
    }

    @Test
    void repositoryLinkIsIndependentOfGovernanceUpdatesAndUnlinksBothFields() {
        BusinessService service = new BusinessService(UUID.randomUUID(), "Example", BusinessCriticality.HIGH);
        RepositoryUrl url = RepositoryUrl.parse("https://github.com/a/b", HOSTS);
        assertThat(service.getRepositoryUrl()).isNull();
        service.linkRepository(url, " develop ");
        service.update("Renamed", BusinessCriticality.LOW, ServiceExposure.ISOLATED);
        assertThat(service.getRepositoryUrl()).isEqualTo(url.canonical());
        assertThat(service.getRepositoryBranch()).isEqualTo("develop");
        assertThatThrownBy(() -> service.linkRepository(url, "-invalid"))
                .isInstanceOf(ApiExceptions.BadRequest.class);
        assertThat(service.getRepositoryBranch()).isEqualTo("develop");
        service.linkRepository(url, " ");
        assertThat(service.getRepositoryBranch()).isNull();
        service.unlinkRepository();
        assertThat(service.getRepositoryUrl()).isNull();
        assertThat(service.getRepositoryBranch()).isNull();
        assertThat(RepositoryUrl.normalizeRef(null)).isNull();
        assertThat(RepositoryUrl.normalizeRef("release/2.0")).isEqualTo("release/2.0");
        assertThatThrownBy(() -> RepositoryUrl.normalizeRef("a".repeat(201)))
                .isInstanceOf(ApiExceptions.BadRequest.class);
    }
}
