create table hunt_query_jobs (
  id varchar(36) primary key,
  owner_id bigint not null,
  scenario_id varchar(8),
  query_json longtext not null,
  result_json longtext,
  state varchar(30) not null,
  error_message varchar(1000),
  created_at timestamp(3) not null,
  updated_at timestamp(3) not null,
  as_of timestamp(3) not null,
  expires_at timestamp(3) not null
);
create index idx_hunt_jobs_state on hunt_query_jobs(state, created_at);
create index idx_hunt_jobs_owner on hunt_query_jobs(owner_id, created_at);

create table hunt_resources (
  id varchar(36) primary key,
  kind varchar(30) not null,
  owner_id bigint not null,
  shared boolean not null default false,
  title varchar(240) not null,
  body_json longtext not null,
  version bigint not null default 1,
  created_at timestamp(3) not null,
  updated_at timestamp(3) not null
);
create index idx_hunt_resources_kind on hunt_resources(kind, updated_at);

create table hunt_evidence (
  id varchar(36) primary key,
  investigation_id varchar(36) not null,
  event_id varchar(64) not null,
  query_job_id varchar(36) not null,
  snapshot_json longtext not null,
  sha256 varchar(64) not null,
  created_by bigint not null,
  created_at timestamp(3) not null,
  expires_at timestamp(3) not null,
  unique key uk_hunt_evidence(investigation_id, event_id),
  foreign key (investigation_id) references hunt_resources(id)
);

create table hunt_audit (
  id bigint primary key auto_increment,
  actor_id bigint not null,
  action varchar(60) not null,
  resource_id varchar(100) not null,
  detail_json longtext not null,
  created_at timestamp(3) not null
);
create index idx_hunt_audit_resource on hunt_audit(resource_id, created_at);

create table hunt_response_requests (
  id varchar(36) primary key,
  investigation_id varchar(36) not null,
  requested_by bigint not null,
  approved_by bigint,
  state varchar(30) not null,
  request_json longtext not null,
  result_json longtext,
  reason varchar(1000) not null,
  created_at timestamp(3) not null,
  updated_at timestamp(3) not null,
  expires_at timestamp(3) not null,
  foreign key (investigation_id) references hunt_resources(id)
);
