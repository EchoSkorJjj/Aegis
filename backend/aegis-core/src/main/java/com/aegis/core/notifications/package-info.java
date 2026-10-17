/**
 * Owner notifications (E9 story 5): when an item in the triage queue ({@code actions.triage}) needs a decision,
 * each of its service's assigned owners is told once — in the app, and by an email identity-service sends
 * ({@code platform.identity.IdentityInternalApi}). A scheduled job looks for new items; nothing here decides or
 * acts on them.
 */
package com.aegis.core.notifications;
