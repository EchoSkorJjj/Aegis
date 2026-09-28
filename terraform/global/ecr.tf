# One ECR repository per service (frontend, gateway, identity-service, aegis-core, ai-service): scan on push,
# and a lifecycle policy keeping the last few images. Environments deploy the same images by tag.
