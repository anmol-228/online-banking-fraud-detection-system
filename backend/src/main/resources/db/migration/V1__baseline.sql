-- =============================================================================
-- V1 baseline: the MySQL 8.4 schema of the Online Banking & Fraud Detection System.
--
-- Derived from the schema Hibernate generated on MySQL and reviewed on 2026-11-05
-- (13 tables, 12 native ENUM columns, 12 identity columns). Only two kinds of line were
-- removed from the raw `mysqldump --no-data` output:
--   * the statements that delete tables - a migration must never be able to destroy data;
--   * the table-level `AUTO_INCREMENT=<n>` counter, which is a snapshot of one machine's
--     history. The COLUMN-level `id bigint NOT NULL AUTO_INCREMENT` is kept on purpose:
--     JPA's @GeneratedValue(strategy = IDENTITY) depends on it.
--
-- FOREIGN_KEY_CHECKS is switched off only while the tables are created, because the
-- alphabetical order of the dump creates some tables before the ones they reference.
-- =============================================================================
SET FOREIGN_KEY_CHECKS = 0;
CREATE TABLE `account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_number` varchar(20) NOT NULL,
  `account_type` enum('CURRENT','SAVINGS') NOT NULL,
  `balance` decimal(15,2) NOT NULL,
  `currency` varchar(3) NOT NULL,
  `opened_at` datetime(6) NOT NULL,
  `status` enum('ACTIVE','BLOCKED','CLOSED') NOT NULL,
  `version` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK66gkcp94endmotfwb8r4ocxm9` (`account_number`),
  KEY `FKnnwpo0lfq4xai1rs6887sx02k` (`customer_id`),
  CONSTRAINT `FKnnwpo0lfq4xai1rs6887sx02k` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `application_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `email` varchar(150) NOT NULL,
  `enabled` bit(1) NOT NULL,
  `full_name` varchar(120) NOT NULL,
  `password_hash` varchar(100) NOT NULL,
  `username` varchar(60) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKcb61p28hanadv7k0nx1ec0n5l` (`email`),
  UNIQUE KEY `UK6c0v0rco93sykgyetukfmkkod` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `application_user_role` (
  `user_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  PRIMARY KEY (`user_id`,`role_id`),
  KEY `FKpm6y739f8al8bakhf9lffvplp` (`role_id`),
  CONSTRAINT `FKcw805inwrlw29qkfe370g5p9s` FOREIGN KEY (`user_id`) REFERENCES `application_user` (`id`),
  CONSTRAINT `FKpm6y739f8al8bakhf9lffvplp` FOREIGN KEY (`role_id`) REFERENCES `user_role` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action` varchar(60) NOT NULL,
  `details` varchar(500) DEFAULT NULL,
  `entity_reference` varchar(40) DEFAULT NULL,
  `entity_type` varchar(40) DEFAULT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `outcome` enum('FAILURE','SUCCESS') NOT NULL,
  `roles` varchar(120) DEFAULT NULL,
  `username` varchar(60) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_audit_username` (`username`),
  KEY `idx_audit_action` (`action`),
  KEY `idx_audit_occurred_at` (`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `bank_transaction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` decimal(15,2) NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `currency` varchar(3) NOT NULL,
  `description` varchar(200) DEFAULT NULL,
  `destination_account_number` varchar(20) NOT NULL,
  `destination_name` varchar(120) NOT NULL,
  `idempotency_key` varchar(80) DEFAULT NULL,
  `initiated_by` varchar(60) NOT NULL,
  `reference` varchar(30) NOT NULL,
  `risk_level` enum('HIGH','LOW','MEDIUM') NOT NULL,
  `risk_reason` varchar(500) DEFAULT NULL,
  `risk_score` int NOT NULL,
  `status` enum('APPROVED','BLOCKED','FAILED','PENDING','PENDING_VERIFICATION') NOT NULL,
  `status_reason` varchar(250) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `beneficiary_id` bigint DEFAULT NULL,
  `source_account_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKgtxr5q3en22ji1vsvcmcrxcea` (`reference`),
  UNIQUE KEY `UK6f50pwriaklm4xqqwmuyb12ik` (`idempotency_key`),
  KEY `idx_txn_source_account` (`source_account_id`),
  KEY `idx_txn_status` (`status`),
  KEY `idx_txn_created_at` (`created_at`),
  KEY `FK1pxs3aa9at75l1csonlm822dg` (`beneficiary_id`),
  CONSTRAINT `FK1pxs3aa9at75l1csonlm822dg` FOREIGN KEY (`beneficiary_id`) REFERENCES `beneficiary` (`id`),
  CONSTRAINT `FKq9l1qaeobmb3ilqj2pddcfq6g` FOREIGN KEY (`source_account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `beneficiary` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `beneficiary_account_number` varchar(20) NOT NULL,
  `active` bit(1) NOT NULL,
  `bank_name` varchar(120) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `ifsc_code` varchar(15) DEFAULT NULL,
  `name` varchar(120) NOT NULL,
  `nickname` varchar(60) DEFAULT NULL,
  `customer_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_beneficiary_customer_account` (`customer_id`,`beneficiary_account_number`),
  CONSTRAINT `FK9k47rotqnxuptgoa8d97as0qo` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `customer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(250) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `customer_number` varchar(20) NOT NULL,
  `email` varchar(150) NOT NULL,
  `full_name` varchar(120) NOT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKmo8uc9hxwlycihsxq6ucdxs1c` (`customer_number`),
  UNIQUE KEY `UKdwk6cx0afu8bs9o4t536v1j5v` (`email`),
  UNIQUE KEY `UKj7ja2xvrxudhvssosd4nu1o92` (`user_id`),
  CONSTRAINT `FKm02uwh0r19ldmel2lgtkjc4g7` FOREIGN KEY (`user_id`) REFERENCES `application_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `dispute` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `description` varchar(1000) NOT NULL,
  `handled_by` varchar(60) DEFAULT NULL,
  `reference` varchar(30) NOT NULL,
  `resolution` varchar(1000) DEFAULT NULL,
  `status` enum('OPEN','REJECTED','RESOLVED','UNDER_REVIEW') NOT NULL,
  `subject` varchar(150) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `customer_id` bigint NOT NULL,
  `transaction_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK8qcah1sgv9pbcfdxcnb4hx90y` (`reference`),
  KEY `FKqj8f3vjx0grcekmccx8mh2onu` (`customer_id`),
  KEY `FKar3tebmlt8x5tcat98wafb271` (`transaction_id`),
  CONSTRAINT `FKar3tebmlt8x5tcat98wafb271` FOREIGN KEY (`transaction_id`) REFERENCES `bank_transaction` (`id`),
  CONSTRAINT `FKqj8f3vjx0grcekmccx8mh2onu` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `fraud_alert` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `reason` varchar(500) NOT NULL,
  `reference` varchar(30) NOT NULL,
  `risk_level` enum('HIGH','LOW','MEDIUM') NOT NULL,
  `risk_score` int NOT NULL,
  `status` enum('CLOSED','OPEN','UNDER_REVIEW') NOT NULL,
  `transaction_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK4abd5mnl01f16rnu24fl5yvon` (`reference`),
  KEY `FKt2kqseic58txmsbsmnuckpfgo` (`transaction_id`),
  CONSTRAINT `FKt2kqseic58txmsbsmnuckpfgo` FOREIGN KEY (`transaction_id`) REFERENCES `bank_transaction` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `fraud_case` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `assigned_to` varchar(60) DEFAULT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `decided_by` varchar(60) DEFAULT NULL,
  `reference` varchar(30) NOT NULL,
  `remarks` varchar(500) DEFAULT NULL,
  `status` enum('OPEN','RESOLVED_APPROVED','RESOLVED_BLOCKED','UNDER_REVIEW') NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `alert_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKdt00bi7v1fb9sp8gygr0dyqxb` (`reference`),
  UNIQUE KEY `UKqak1bat0e4j6br8ss6xhff7xd` (`alert_id`),
  CONSTRAINT `FKa04k4o3kxql8n6uxy281yjyas` FOREIGN KEY (`alert_id`) REFERENCES `fraud_alert` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `message` varchar(500) NOT NULL,
  `read_flag` bit(1) NOT NULL,
  `related_reference` varchar(30) DEFAULT NULL,
  `title` varchar(150) NOT NULL,
  `type` enum('DISPUTE','FRAUD_ALERT','GENERAL','SECURITY','TRANSACTION','VERIFICATION') NOT NULL,
  `customer_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_notification_customer` (`customer_id`),
  CONSTRAINT `FKduo4ugsictahhrd219y86wqho` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `description` varchar(200) DEFAULT NULL,
  `name` enum('BANK_ADMIN','CUSTOMER','FRAUD_ANALYST','OPS_OFFICER','SYSTEM_ADMIN') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKlnth8w122wgy7grrjlu8hjmuu` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `verification_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `attempts` int NOT NULL,
  `code_hash` varchar(100) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `max_attempts` int NOT NULL,
  `status` enum('EXPIRED','FAILED','PENDING','VERIFIED') NOT NULL,
  `verified_at` datetime(6) DEFAULT NULL,
  `transaction_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKjs3osfvqd7sg9cyqglbqfaxyj` (`transaction_id`),
  CONSTRAINT `FKjs3osfvqd7sg9cyqglbqfaxyj` FOREIGN KEY (`transaction_id`) REFERENCES `bank_transaction` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
SET FOREIGN_KEY_CHECKS = 1;
