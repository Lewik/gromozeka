variable "aws_region" {
  description = "AWS region for the Gromozeka deployment."
  type        = string
  default     = "il-central-1"
}

variable "project_name" {
  description = "Stable resource-name prefix."
  type        = string
  default     = "gromozeka"
}

variable "environment" {
  description = "Deployment environment name."
  type        = string
  default     = "primary"
}

variable "github_repository" {
  description = "GitHub owner/repository allowed to assume deployment roles."
  type        = string
  default     = "Lewik/gromozeka"
}

variable "github_branch" {
  description = "GitHub branch allowed to build cloud images."
  type        = string
  default     = "main"
}

variable "github_deploy_environment" {
  description = "GitHub environment allowed to deploy."
  type        = string
  default     = "aws-production"
}

variable "instance_type" {
  description = "EC2 instance type for Server, Cloud Worker, PostgreSQL and RabbitMQ."
  type        = string
  default     = "t3a.medium"
}

variable "root_volume_size_gib" {
  description = "Encrypted root volume size."
  type        = number
  default     = 20
}

variable "data_volume_size_gib" {
  description = "Persistent encrypted data volume size."
  type        = number
  default     = 50
}

variable "vpc_cidr" {
  description = "Dedicated Gromozeka VPC CIDR."
  type        = string
  default     = "10.42.0.0/16"
}

variable "subnet_cidr" {
  description = "Public subnet CIDR. The instance has no inbound security-group rules."
  type        = string
  default     = "10.42.1.0/24"
}

variable "budget_limit_usd" {
  description = "Optional monthly AWS budget. Set budget_alert_email to create it."
  type        = number
  default     = 100
}

variable "budget_alert_email" {
  description = "Email for AWS budget alerts. Empty disables budget creation."
  type        = string
  default     = ""
  sensitive   = true
}
