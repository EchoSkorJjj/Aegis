# SecureString parameters under /aegis/<environment>/ for every secret the services read: the token signing
# key, the GitHub App key and client secret, the Jira client secret, the secrets key, the Groq key, the service
# token between aegis-core and ai-service, the TOTP key, the database passwords, the bootstrap admin password.
# Values are write-only, or left for filling by hand, so no secret reaches state.
