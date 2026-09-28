# Non-secret values for dev (committed). Secrets never go in a tfvars file: they are SSM parameters.
# environment   = "dev"
# aws_region    = "<region>"
# domain_name   = "dev.aegisops.pro"
# vpc_cidr      = "10.40.0.0/16"
# capacity      = "spot"           # FARGATE_SPOT day to day; "on_demand" for a demo
# desired_count = 1                # 0 stops the services between demos
# image_tag     = "<git sha>"
# db_instance_class   = "db.t4g.micro"
# deletion_protection = false
