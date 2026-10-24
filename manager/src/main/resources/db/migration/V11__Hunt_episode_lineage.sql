alter table hunt_findings add column version bigint not null default 1;
alter table hunt_findings add column merged_into varchar(36);
create index idx_hunt_finding_alias on hunt_findings(merged_into);

create table hunt_finding_occurrences (
  id bigint primary key auto_increment,
  finding_id varchar(36) not null,
  rule_id varchar(36) not null,
  rule_version bigint not null,
  job_id varchar(36) not null,
  candidate_sha256 varchar(64) not null,
  origin varchar(30) not null default 'EXECUTION',
  snapshot_json longtext not null,
  snapshot_sha256 varchar(64) not null,
  created_at timestamp(3) not null,
  unique key uk_hunt_finding_occurrence(rule_id,rule_version,job_id,candidate_sha256),
  foreign key (finding_id) references hunt_findings(id) on delete cascade
);
create index idx_hunt_occurrence_page on hunt_finding_occurrences(finding_id,id);

create table hunt_finding_merges (
  id bigint primary key auto_increment,
  source_id varchar(36) not null,
  target_id varchar(36) not null,
  job_id varchar(36) not null,
  actor_id bigint not null,
  source_json longtext not null,
  source_sha256 varchar(64) not null,
  created_at timestamp(3) not null,
  foreign key (source_id) references hunt_findings(id) on delete cascade,
  foreign key (target_id) references hunt_findings(id) on delete cascade
);
create index idx_hunt_merge_page on hunt_finding_merges(target_id,id);
