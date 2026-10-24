create table hunt_exceptions (
  id varchar(36) primary key,
  scenario_id varchar(8) not null,
  scenario_version varchar(40) not null,
  scope_key varchar(64) not null,
  requested_by bigint not null,
  approved_by bigint,
  state varchar(30) not null,
  version bigint not null,
  valid_from timestamp(3) not null,
  expires_at timestamp(3) not null,
  created_at timestamp(3) not null,
  updated_at timestamp(3) not null,
  approved_at timestamp(3),
  payload_json longtext not null,
  evidence_sha256 varchar(64) not null
);
create index idx_hunt_exception_scope on hunt_exceptions(scenario_id,scenario_version,scope_key,state,expires_at);
create index idx_hunt_exception_page on hunt_exceptions(created_at,id);
create index idx_hunt_exception_owner on hunt_exceptions(requested_by,state,expires_at);
create table hunt_exception_history (
  id bigint primary key auto_increment,
  exception_id varchar(36) not null,
  version bigint not null,
  state varchar(30) not null,
  actor_id bigint not null,
  note varchar(4000) not null,
  created_at timestamp(3) not null,
  foreign key (exception_id) references hunt_exceptions(id),
  unique key uk_hunt_exception_history(exception_id,version)
);
