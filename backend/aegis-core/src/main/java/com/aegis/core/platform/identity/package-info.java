/**
 * Identity lookups — aegis-core's read-only window onto identity-service's member directory
 * ({@link com.aegis.core.platform.identity.IdentityDirectory}). Used wherever aegis-core must turn a
 * client-supplied user id into a verified member of the caller's organization (e.g. assigning a
 * service's owners). Calls relay the caller's own token, so identity-service's org scoping applies.
 */
package com.aegis.core.platform.identity;
