# One security group per tier and the rules between them: internet to the ALB on 443 (and 80, redirected); ALB
# to frontend 3000; frontend to gateway 8080; gateway to identity-service 8081 and aegis-core 8082; aegis-core
# to identity-service 8081 and ai-service 8083; identity-service and aegis-core to RDS 5432; private subnets
# out through the NAT instance.
