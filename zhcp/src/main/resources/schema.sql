CREATE TABLE IF NOT EXISTS sys_user (
  user_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_name VARCHAR(64) NOT NULL UNIQUE,
  nick_name VARCHAR(64) NOT NULL,
  role VARCHAR(32) NOT NULL COMMENT 'secretary/student',
  student_no VARCHAR(32) DEFAULT NULL,
  college VARCHAR(128) DEFAULT NULL,
  class_name VARCHAR(64) DEFAULT NULL,
  campus_account VARCHAR(64) DEFAULT NULL,
  campus_password_enc VARCHAR(512) DEFAULT NULL,
  status CHAR(1) DEFAULT '0',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_student_no (student_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS zhcp_class (
  class_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  college VARCHAR(128) NOT NULL,
  class_name VARCHAR(64) NOT NULL,
  secretary_user_id BIGINT DEFAULT NULL,
  semester VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_class (college, class_name, semester)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS zhcp_roster (
  roster_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  student_no VARCHAR(32) NOT NULL,
  student_name VARCHAR(64) NOT NULL,
  registered TINYINT DEFAULT 0,
  user_id BIGINT DEFAULT NULL,
  UNIQUE KEY uk_roster (class_id, student_no),
  KEY idx_name (student_name, student_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS zhcp_rule_pack (
  pack_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  college VARCHAR(128) NOT NULL,
  grade_year VARCHAR(16) DEFAULT '2024',
  pack_code VARCHAR(64) NOT NULL UNIQUE,
  title VARCHAR(256) NOT NULL,
  content_json LONGTEXT NOT NULL,
  enabled TINYINT DEFAULT 1,
  confirmed TINYINT DEFAULT 1,
  source_name VARCHAR(256) DEFAULT NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE zhcp_rule_pack ADD COLUMN confirmed TINYINT DEFAULT 1;
ALTER TABLE zhcp_rule_pack ADD COLUMN source_name VARCHAR(256) DEFAULT NULL;

CREATE TABLE IF NOT EXISTS zhcp_score (
  score_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  roster_id BIGINT NOT NULL,
  class_id BIGINT NOT NULL,
  semester VARCHAR(32) NOT NULL,
  moral_peer DECIMAL(6,2) DEFAULT 0,
  moral_reward DECIMAL(6,2) DEFAULT 0,
  moral_deduct DECIMAL(6,2) DEFAULT 0,
  academic_base DECIMAL(6,2) DEFAULT 0,
  academic_reward DECIMAL(6,2) DEFAULT 0,
  academic_deduct DECIMAL(6,2) DEFAULT 0,
  body_pe DECIMAL(6,2) DEFAULT 0,
  body_mental DECIMAL(6,2) DEFAULT 0,
  body_labor DECIMAL(6,2) DEFAULT 0,
  body_reward DECIMAL(6,2) DEFAULT 0,
  body_deduct DECIMAL(6,2) DEFAULT 0,
  develop_work DECIMAL(6,2) DEFAULT 0,
  develop_talent DECIMAL(6,2) DEFAULT 0,
  develop_tech DECIMAL(6,2) DEFAULT 0,
  total_score DECIMAL(6,2) DEFAULT 0,
  major_rank INT DEFAULT NULL,
  zhcp_rank INT DEFAULT NULL,
  signed TINYINT DEFAULT 0,
  UNIQUE KEY uk_score (roster_id, semester)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS zhcp_item (
  item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  score_id BIGINT NOT NULL,
  category VARCHAR(32) NOT NULL,
  title VARCHAR(256) NOT NULL,
  clause VARCHAR(128) DEFAULT NULL,
  raw_points DECIMAL(6,2) DEFAULT 0,
  applied_points DECIMAL(6,2) DEFAULT 0,
  authenticity VARCHAR(32) DEFAULT 'pending',
  authenticity_reason VARCHAR(512) DEFAULT NULL,
  suggested TINYINT DEFAULT 0,
  confirmed TINYINT DEFAULT 0,
  rejected TINYINT DEFAULT 0,
  evidence_ids VARCHAR(512) DEFAULT NULL,
  extra_json TEXT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS zhcp_file (
  file_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  score_id BIGINT DEFAULT NULL,
  user_id BIGINT NOT NULL,
  original_name VARCHAR(256) NOT NULL,
  stored_path VARCHAR(512) NOT NULL,
  content_type VARCHAR(128) DEFAULT NULL,
  material_type VARCHAR(64) DEFAULT NULL,
  authenticity VARCHAR(32) DEFAULT 'pending',
  authenticity_reason VARCHAR(1024) DEFAULT NULL,
  extracted_json LONGTEXT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS zhcp_audit (
  audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  action VARCHAR(64) NOT NULL,
  target VARCHAR(128) DEFAULT NULL,
  detail TEXT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
