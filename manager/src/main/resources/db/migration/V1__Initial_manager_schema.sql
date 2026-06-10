create table users (
  id bigint primary key auto_increment,
  username varchar(120) not null unique,
  password_hash varchar(255) not null,
  display_name varchar(200),
  role varchar(40) not null,
  enabled boolean not null default true,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp
);

create table user_sessions (
  id bigint primary key auto_increment,
  session_token_hash varchar(255) not null unique,
  user_id bigint not null,
  expires_at timestamp not null,
  created_at timestamp not null default current_timestamp,
  foreign key (user_id) references users(id)
);

create table integration_configs (
  id bigint primary key auto_increment,
  service_name varchar(80) not null unique,
  base_url varchar(500) not null,
  encrypted_secret text,
  enabled boolean not null default true,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp
);

create table assets (
  id bigint primary key auto_increment,
  asset_uid varchar(120) not null unique,
  name varchar(255) not null,
  asset_type varchar(80) not null,
  management_ip varchar(80),
  description text,
  status varchar(40) not null default 'unknown',
  first_seen_at timestamp null,
  last_seen_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp
);

create table asset_source_bindings (
  id bigint primary key auto_increment,
  asset_id bigint not null,
  source_type varchar(40) not null,
  source_id varchar(255) not null,
  source_key varchar(255),
  confidence int not null default 100,
  last_seen_at timestamp null,
  created_at timestamp not null default current_timestamp,
  unique key uk_asset_source (source_type, source_id, source_key),
  foreign key (asset_id) references assets(id)
);

create table asset_merge_candidates (
  id bigint primary key auto_increment,
  primary_asset_id bigint not null,
  candidate_asset_id bigint not null,
  reason varchar(500) not null,
  confidence int not null,
  status varchar(40) not null default 'pending',
  created_at timestamp not null default current_timestamp,
  foreign key (primary_asset_id) references assets(id),
  foreign key (candidate_asset_id) references assets(id)
);

create table snmp_credentials (
  id bigint primary key auto_increment,
  name varchar(200) not null,
  version varchar(20) not null,
  encrypted_params text not null,
  created_at timestamp not null default current_timestamp
);

create table snmp_targets (
  id bigint primary key auto_increment,
  name varchar(200) not null,
  host varchar(255) not null,
  port int not null default 161,
  credential_id bigint,
  enabled boolean not null default true,
  poll_interval_ms bigint not null default 60000,
  logparser_adapter_id bigint,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  foreign key (credential_id) references snmp_credentials(id)
);

create table alert_rules (
  id bigint primary key auto_increment,
  name varchar(200) not null,
  rule_type varchar(80) not null,
  severity varchar(40) not null,
  expression_json text not null,
  enabled boolean not null default true,
  created_at timestamp not null default current_timestamp
);

create table alert_instances (
  id bigint primary key auto_increment,
  rule_id bigint not null,
  asset_id bigint,
  severity varchar(40) not null,
  status varchar(40) not null,
  title varchar(300) not null,
  detail text,
  state_key varchar(255),
  first_seen_at timestamp not null,
  last_seen_at timestamp not null,
  acknowledged_at timestamp null,
  resolved_at timestamp null,
  foreign key (rule_id) references alert_rules(id),
  foreign key (asset_id) references assets(id)
);

create table sync_cursors (
  name varchar(120) primary key,
  cursor_value varchar(500) not null,
  updated_at timestamp not null default current_timestamp
);
