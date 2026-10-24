create table hunt_case_workflow (
  investigation_id varchar(36) primary key,
  assignee_id bigint,
  reviewer_id bigint,
  last_editor_id bigint not null,
  foreign key (investigation_id) references hunt_resources(id) on delete cascade,
  foreign key (assignee_id) references users(id),
  foreign key (reviewer_id) references users(id)
);
create index idx_hunt_case_assignee on hunt_case_workflow(assignee_id);
create index idx_hunt_case_reviewer on hunt_case_workflow(reviewer_id);

insert into hunt_case_workflow(investigation_id,last_editor_id)
select id,owner_id from hunt_resources where kind='investigation';

create table hunt_case_reviews (
  id bigint primary key auto_increment,
  investigation_id varchar(36) not null,
  case_version bigint not null,
  submitted_by bigint not null,
  reviewer_id bigint not null,
  state varchar(30) not null,
  request_note varchar(4000) not null,
  decision_note varchar(4000),
  snapshot_json longtext not null,
  sha256 varchar(64) not null,
  created_at timestamp(3) not null,
  decided_at timestamp(3),
  decided_by bigint,
  foreign key (investigation_id) references hunt_resources(id) on delete cascade,
  unique key uk_hunt_case_review_version(investigation_id,case_version)
);
create index idx_hunt_case_reviews on hunt_case_reviews(investigation_id,id);
