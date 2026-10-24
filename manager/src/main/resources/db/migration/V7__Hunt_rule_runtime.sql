create table hunt_rule_runtime (
  rule_id varchar(36) primary key,
  rule_version bigint not null,
  observed_since timestamp(3),
  ready_runs int not null default 0,
  last_started_at timestamp(3),
  last_finished_at timestamp(3),
  last_job_id varchar(36),
  last_state varchar(40) not null default 'IDLE',
  last_error varchar(1000),
  alert_rule_id bigint,
  foreign key (rule_id) references hunt_resources(id)
);
create table hunt_findings (
  id varchar(36) primary key,
  rule_id varchar(36) not null,
  rule_version bigint not null,
  group_key varchar(64) not null,
  scenario_id varchar(8) not null,
  first_seen_at timestamp(3) not null,
  last_seen_at timestamp(3) not null,
  last_job_id varchar(36) not null,
  payload_json longtext not null,
  status varchar(30) not null default 'OPEN',
  alert_id bigint,
  foreign key (rule_id) references hunt_resources(id)
);
create index idx_hunt_finding_episode on hunt_findings(rule_id, rule_version, group_key, last_seen_at);
create table hunt_lookup_names (
  name varchar(80) primary key,
  resource_id varchar(36) not null unique,
  foreign key (resource_id) references hunt_resources(id)
);
