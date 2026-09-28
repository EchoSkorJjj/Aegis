# The internet-facing Application Load Balancer in the public subnets: an HTTPS listener with an ACM
# certificate (DNS-validated in the zone), HTTP redirected to HTTPS, idle timeout 300 s (a repository SBOM
# import can run for minutes), the frontend's target group, and the Route 53 alias record for the environment's
# host name.
