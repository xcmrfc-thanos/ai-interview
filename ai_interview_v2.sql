/*
 Navicat Premium Dump SQL

 Source Server         : APP
 Source Server Type    : MySQL
 Source Server Version : 80037 (8.0.37)
 Source Host           : localhost:3306
 Source Schema         : ai_interview_v2

 Target Server Type    : MySQL
 Target Server Version : 80037 (8.0.37)
 File Encoding         : 65001

 Date: 09/04/2025 20:20:30
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for ai_interviews
-- ----------------------------
DROP TABLE IF EXISTS `ai_interviews`;
CREATE TABLE `ai_interviews`  (
  `interview_id` int NOT NULL AUTO_INCREMENT,
  `application_id` int NOT NULL,
  `start_time` datetime NOT NULL,
  `end_time` datetime NULL DEFAULT NULL,
  `history` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `evaluation` json NULL COMMENT '评估JSON数据',
  `overall_score` float NULL DEFAULT NULL COMMENT '总分',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`interview_id`) USING BTREE,
  INDEX `idx_interviews_time`(`start_time` ASC) USING BTREE,
  INDEX `application_id`(`application_id` ASC) USING BTREE,
  CONSTRAINT `ai_interviews_ibfk_1` FOREIGN KEY (`application_id`) REFERENCES `applications` (`application_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 14 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for applicants
-- ----------------------------
DROP TABLE IF EXISTS `applicants`;
CREATE TABLE `applicants`  (
  `applicant_id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '关联用户账号',
  `full_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `resume_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '简历文件存储路径',
  `gender` enum('male','female','other') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `birthdate` date NULL DEFAULT NULL,
  `education_level` enum('高中','大专','本科','硕士','博士') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `work_years` tinyint NULL DEFAULT 0,
  `expected_position` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `expected_salary` int NULL DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`applicant_id`) USING BTREE,
  UNIQUE INDEX `user_id`(`user_id` ASC) USING BTREE,
  CONSTRAINT `applicants_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for applications
-- ----------------------------
DROP TABLE IF EXISTS `applications`;
CREATE TABLE `applications`  (
  `application_id` int NOT NULL AUTO_INCREMENT,
  `job_id` int NOT NULL,
  `applicant_id` int NOT NULL,
  `apply_time` datetime NOT NULL,
  `status` enum('已投递','已查看','面试中','待定','录用','拒绝') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '已投递',
  `ai_evaluation_score` float NULL DEFAULT NULL COMMENT 'AI综合评分',
  `feedback` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '企业反馈',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `resume_id` int NULL DEFAULT NULL,
  PRIMARY KEY (`application_id`) USING BTREE,
  UNIQUE INDEX `job_id`(`job_id` ASC, `applicant_id` ASC) USING BTREE,
  INDEX `applicant_id`(`applicant_id` ASC) USING BTREE,
  INDEX `idx_applications_status`(`status` ASC) USING BTREE,
  INDEX `fk_resume2`(`resume_id` ASC) USING BTREE,
  CONSTRAINT `applications_ibfk_1` FOREIGN KEY (`job_id`) REFERENCES `jobs` (`job_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `applications_ibfk_2` FOREIGN KEY (`applicant_id`) REFERENCES `applicants` (`applicant_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_resume2` FOREIGN KEY (`resume_id`) REFERENCES `resumes` (`resume_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 12 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for companies
-- ----------------------------
DROP TABLE IF EXISTS `companies`;
CREATE TABLE `companies`  (
  `company_id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '关联管理员账号',
  `company_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `industry` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '所属行业',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `scale` enum('1-50人','51-100人','101-500人','500人以上') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `website` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `contact_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `logo_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`company_id`) USING BTREE,
  UNIQUE INDEX `company_name`(`company_name` ASC) USING BTREE,
  INDEX `user_id`(`user_id` ASC) USING BTREE,
  CONSTRAINT `companies_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for interview_questions
-- ----------------------------
DROP TABLE IF EXISTS `interview_questions`;
CREATE TABLE `interview_questions`  (
  `question_id` int NOT NULL AUTO_INCREMENT,
  `job_id` int NOT NULL,
  `question_type` enum('技术','行为','情景模拟','通用') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `reference_answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `difficulty_level` enum('简单','中等','困难') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`question_id`) USING BTREE,
  INDEX `idx_job_id`(`job_id` ASC) USING BTREE,
  CONSTRAINT `fk_question_job` FOREIGN KEY (`job_id`) REFERENCES `jobs` (`job_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for interview_scores
-- ----------------------------
DROP TABLE IF EXISTS `interview_scores`;
CREATE TABLE `interview_scores`  (
  `score_id` int NOT NULL AUTO_INCREMENT,
  `interview_id` int NOT NULL,
  `technical_ability` float NOT NULL COMMENT '技术能力',
  `learning_ability` float NOT NULL COMMENT '学习能力',
  `team_collaboration` float NOT NULL COMMENT '团队协作能力',
  `problem_solving` float NOT NULL COMMENT '问题解决能力',
  `communication_expression` float NOT NULL COMMENT '沟通表达能力',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`score_id`) USING BTREE,
  INDEX `interview_id`(`interview_id` ASC) USING BTREE,
  CONSTRAINT `interview_scores_ibfk_1` FOREIGN KEY (`interview_id`) REFERENCES `ai_interviews` (`interview_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `interview_scores_chk_1` CHECK (`technical_ability` between 0 and 100),
  CONSTRAINT `interview_scores_chk_2` CHECK (`learning_ability` between 0 and 100),
  CONSTRAINT `interview_scores_chk_3` CHECK (`team_collaboration` between 0 and 100),
  CONSTRAINT `interview_scores_chk_4` CHECK (`problem_solving` between 0 and 100),
  CONSTRAINT `interview_scores_chk_5` CHECK (`communication_expression` between 0 and 100)
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for jobs
-- ----------------------------
DROP TABLE IF EXISTS `jobs`;
CREATE TABLE `jobs`  (
  `job_id` int NOT NULL AUTO_INCREMENT,
  `company_id` int NOT NULL,
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `job_type` enum('全职','兼职','实习') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `requirements` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `min_salary` int NULL DEFAULT NULL,
  `max_salary` int NULL DEFAULT NULL,
  `location` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `is_active` tinyint(1) NULL DEFAULT 1 COMMENT '职位是否有效',
  `post_date` date NOT NULL,
  `expiration_date` date NULL DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`job_id`) USING BTREE,
  INDEX `idx_jobs_company`(`company_id` ASC) USING BTREE,
  CONSTRAINT `jobs_ibfk_1` FOREIGN KEY (`company_id`) REFERENCES `companies` (`company_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for report
-- ----------------------------
DROP TABLE IF EXISTS `report`;
CREATE TABLE `report`  (
  `report_id` int NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `report` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `resume_id` int NOT NULL COMMENT '外键关联简历',
  `job_id` int NOT NULL COMMENT '外键关联职位',
  PRIMARY KEY (`report_id`) USING BTREE,
  INDEX `fk_resume`(`resume_id` ASC) USING BTREE,
  INDEX `fk_job`(`job_id` ASC) USING BTREE,
  CONSTRAINT `fk_job` FOREIGN KEY (`job_id`) REFERENCES `jobs` (`job_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_resume` FOREIGN KEY (`resume_id`) REFERENCES `resumes` (`resume_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for resume_scores
-- ----------------------------
DROP TABLE IF EXISTS `resume_scores`;
CREATE TABLE `resume_scores`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `report_id` int NOT NULL,
  `total_score` decimal(5, 2) NOT NULL DEFAULT 0.00 COMMENT '总分',
  `tech_match` decimal(5, 2) NOT NULL DEFAULT 0.00 COMMENT '技术匹配度得分',
  `experience_match` decimal(5, 2) NOT NULL DEFAULT 0.00 COMMENT '经验匹配度得分',
  `education_match` decimal(5, 2) NOT NULL DEFAULT 0.00 COMMENT '教育匹配度得分',
  `potential_match` decimal(5, 2) NOT NULL DEFAULT 0.00 COMMENT '发展潜力匹配度得分',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `report_id`(`report_id` ASC) USING BTREE,
  CONSTRAINT `resume_scores_ibfk_1` FOREIGN KEY (`report_id`) REFERENCES `report` (`report_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for resumes
-- ----------------------------
DROP TABLE IF EXISTS `resumes`;
CREATE TABLE `resumes`  (
  `resume_id` int NOT NULL AUTO_INCREMENT,
  `applicant_id` int NOT NULL,
  `file_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `filename` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `upload_date` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `parsed_data` json NULL,
  PRIMARY KEY (`resume_id`) USING BTREE,
  INDEX `applicant_id`(`applicant_id` ASC) USING BTREE,
  CONSTRAINT `resumes_ibfk_1` FOREIGN KEY (`applicant_id`) REFERENCES `applicants` (`applicant_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for score_weight
-- ----------------------------
DROP TABLE IF EXISTS `score_weights`;
CREATE TABLE `score_weights` (
    `weight_id` int NOT NULL AUTO_INCREMENT,
    `job_id` int NOT NULL,
    `technical_weight` decimal(5,2) NOT NULL DEFAULT 0.40 COMMENT '专业技术能力权重',
    `learning_weight` decimal(5,2) NOT NULL DEFAULT 0.20 COMMENT '学习能力权重',
    `team_weight` decimal(5,2) NOT NULL DEFAULT 0.15 COMMENT '团队协作权重',
    `problem_solving_weight` decimal(5,2) NOT NULL DEFAULT 0.15 COMMENT '问题解决权重',
    `communication_weight` decimal(5,2) NOT NULL DEFAULT 0.10 COMMENT '沟通表达权重',
    `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`weight_id`) USING BTREE,
    UNIQUE KEY `idx_job_weight` (`job_id`) USING BTREE,
    CONSTRAINT `fk_weights_job` FOREIGN KEY (`job_id`) REFERENCES `jobs` (`job_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_weights_sum` CHECK (
        technical_weight + learning_weight + team_weight + 
        problem_solving_weight + communication_weight = 1.00
    ),
    CONSTRAINT `chk_technical_weight` CHECK (technical_weight BETWEEN 0 AND 1),
    CONSTRAINT `chk_learning_weight` CHECK (learning_weight BETWEEN 0 AND 1),
    CONSTRAINT `chk_team_weight` CHECK (team_weight BETWEEN 0 AND 1),
    CONSTRAINT `chk_problem_solving_weight` CHECK (problem_solving_weight BETWEEN 0 AND 1),
    CONSTRAINT `chk_communication_weight` CHECK (communication_weight BETWEEN 0 AND 1)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for users
-- ----------------------------
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users`  (
  `user_id` int NOT NULL AUTO_INCREMENT,
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `role` enum('applicant','company') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`) USING BTREE,
  UNIQUE INDEX `email`(`email` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 12 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

SET FOREIGN_KEY_CHECKS = 1;
