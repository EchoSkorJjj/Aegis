locals {
  name      = "aegis-${var.environment}"
  app_url   = "https://${var.domain_name}"
  namespace = "${var.environment}.aegis.internal"

  # ALBs and RDS subnet groups need two availability zones.
  availability_zones = slice(data.aws_availability_zones.available.names, 0, 2)

  # Each service's port, and where the others reach it: service discovery in the private namespace.
  ports = {
    frontend           = 3000
    gateway            = 8080
    "identity-service" = 8081
    "aegis-core"       = 8082
    "ai-service"       = 8083
  }
  urls = { for service, port in local.ports : service => "http://${service}.${local.namespace}:${port}" }

  # The ALB checks the frontend here; a redirect to the sign-in page counts as healthy.
  frontend_health_check_path = "/"

  # The database the one-time role setup creates (todo/aws-deployment.md); each service connects as its own role.
  jdbc_url = "jdbc:postgresql://${module.rds.address}:${module.rds.port}/aegis"

  # The gateway passes X-Forwarded-For on only from the VPC's addresses (its ForwardedForFilter), e.g. 10\.40\.\d+\.\d+.
  vpc_octets              = split(".", cidrhost(var.vpc_cidr, 0))
  gateway_trusted_proxies = "${local.vpc_octets[0]}\\.${local.vpc_octets[1]}\\.\\d+\\.\\d+"

  # What each service runs with. The environment is plain configuration; secrets are in secret_candidates below.
  services = {
    frontend = {
      cpu    = 256
      memory = 512
      environment = tomap({
        NODE_ENV           = "production"
        HOSTNAME           = "0.0.0.0" # listen on every interface, whatever host name the task gets
        AUTH_COOKIE_SECURE = "true"
        GATEWAY_URL        = local.urls.gateway
        TRUSTED_PROXY_HOPS = "1"
        # Longer than the ALB's idle timeout, or Node closes connections the ALB still reuses (502s).
        KEEP_ALIVE_TIMEOUT = tostring((var.alb_idle_timeout + 5) * 1000)
        PRIVACY_OPERATOR   = var.privacy_operator
        PRIVACY_CONTACT    = var.privacy_contact
      })
    }

    gateway = {
      cpu    = 512
      memory = 1024
      environment = tomap({
        IDENTITY_URI            = local.urls["identity-service"]
        CORE_URI                = local.urls["aegis-core"]
        IDENTITY_JWKS_URI       = "${local.urls["identity-service"]}/oauth2/jwks"
        GATEWAY_TRUSTED_PROXIES = local.gateway_trusted_proxies
        TRACING_SAMPLE_RATE     = "0"
        JAVA_TOOL_OPTIONS       = "-XX:MaxRAMPercentage=70"
      })
    }

    "identity-service" = {
      cpu    = 512
      memory = 1024
      environment = tomap({
        DB_URL                                           = local.jdbc_url
        DB_USER                                          = "aegis_identity"
        IDENTITY_ISSUER                                  = local.app_url
        IDENTITY_FRONTEND_BASE_URL                       = local.app_url
        CORS_ALLOWED_ORIGINS                             = local.app_url
        WEBAUTHN_RP_ID                                   = var.domain_name
        WEBAUTHN_ORIGIN                                  = local.app_url
        MFA_TOTP_ENABLED                                 = "true"
        MFA_WEBAUTHN_ENABLED                             = "true"
        REGISTRATION_ENABLED                             = tostring(var.registration_enabled)
        IDENTITY_BOOTSTRAP_ADMIN_EMAIL                   = var.bootstrap_admin_email
        IDENTITY_MAIL_FROM                               = "no-reply@${var.root_domain}"
        SPRING_MAIL_HOST                                 = "email-smtp.${var.aws_region}.amazonaws.com"
        SPRING_MAIL_PORT                                 = "587"
        SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH            = "true"
        SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE = "true"
        TRACING_SAMPLE_RATE                              = "0"
        JAVA_TOOL_OPTIONS                                = "-XX:MaxRAMPercentage=70"
      })
    }

    "aegis-core" = {
      cpu    = 1024
      memory = 2048
      environment = tomap({
        DB_URL                                = local.jdbc_url
        DB_USER                               = "aegis_core"
        IDENTITY_JWKS_URI                     = "${local.urls["identity-service"]}/oauth2/jwks"
        IDENTITY_BASE_URL                     = local.urls["identity-service"]
        AI_SERVICE_URI                        = local.urls["ai-service"]
        CORS_ALLOWED_ORIGINS                  = local.app_url
        AEGIS_DEMO_SEED_ENABLED               = "false"
        AEGIS_INGESTION_NVD_ENABLED           = "true"
        AEGIS_INGESTION_OSV_ENABLED           = "true"
        AEGIS_OSV_ECOSYSTEMS                  = var.osv_ecosystems
        AEGIS_INGESTION_VENDOR_STATUS_ENABLED = "true"
        AEGIS_INGESTION_NEWS_ENABLED          = "true"
        AEGIS_GITHUB_ENABLED                  = tostring(var.github_app_enabled)
        AEGIS_GITHUB_APP_CLIENT_ID            = var.github_app_client_id
        AEGIS_GITHUB_CALLBACK_URL             = "${local.app_url}/admin/integrations/github/callback"
        AEGIS_JIRA_ENABLED                    = tostring(var.jira_enabled)
        AEGIS_JIRA_CLIENT_ID                  = var.jira_client_id
        AEGIS_JIRA_CALLBACK_URL               = "${local.app_url}/admin/integrations/jira/callback"
        TRACING_SAMPLE_RATE                   = "0"
        # git and syft run beside the JVM in this container, so the heap gets less of it.
        JAVA_TOOL_OPTIONS = "-XX:MaxRAMPercentage=60"
      })
    }

    "ai-service" = {
      cpu    = 256
      memory = 512
      environment = tomap({
        LLM_PROVIDER = var.llm_provider
        GROQ_MODEL   = var.groq_model
      })
    }
  }

  # The secrets each service reads: environment variable => SSM parameter (under /aegis/<environment>/), and
  # whether it's needed. Only what's needed gets a parameter; a person fills its value in (see outputs).
  secret_candidates = {
    frontend = {}
    gateway  = {}

    "identity-service" = {
      DB_PASSWORD                       = { parameter = "identity/db-password", needed = true }
      IDENTITY_JWT_PRIVATE_KEY          = { parameter = "identity/jwt-private-key", needed = true }
      MFA_TOTP_SECRET_KEY               = { parameter = "identity/mfa-totp-secret-key", needed = true }
      SPRING_MAIL_USERNAME              = { parameter = "identity/smtp-username", needed = true }
      SPRING_MAIL_PASSWORD              = { parameter = "identity/smtp-password", needed = true }
      IDENTITY_BOOTSTRAP_ADMIN_PASSWORD = { parameter = "identity/bootstrap-admin-password", needed = var.bootstrap_admin_email != "" }
    }

    "aegis-core" = {
      DB_PASSWORD                    = { parameter = "core/db-password", needed = true }
      AI_SERVICE_TOKEN               = { parameter = "ai-service-token", needed = true }
      AEGIS_GITHUB_APP_CLIENT_SECRET = { parameter = "core/github-app-client-secret", needed = var.github_app_enabled }
      AEGIS_GITHUB_APP_PRIVATE_KEY   = { parameter = "core/github-app-private-key", needed = var.github_app_enabled }
      AEGIS_JIRA_CLIENT_SECRET       = { parameter = "core/jira-client-secret", needed = var.jira_enabled }
      AEGIS_SECRETS_KEY              = { parameter = "core/secrets-key", needed = var.jira_enabled }
      AEGIS_NVD_API_KEY              = { parameter = "core/nvd-api-key", needed = var.nvd_api_key_enabled }
    }

    "ai-service" = {
      SERVICE_TOKEN = { parameter = "ai-service-token", needed = true }
      GROQ_API_KEY  = { parameter = "ai/groq-api-key", needed = var.llm_provider == "groq" }
    }
  }

  service_secrets = {
    for service, candidates in local.secret_candidates :
    service => { for variable, secret in candidates : variable => secret.parameter if secret.needed }
  }
  secret_names = toset(flatten([for secrets in values(local.service_secrets) : values(secrets)]))
}
