package com.aegis.core.actions.pr;

/** A provider's reference to a created draft pull/merge request. */
public record PullRequestRef(String provider, String url, String number, String headBranch) {
}
