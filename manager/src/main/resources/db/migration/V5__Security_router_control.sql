create table router_control_bindings (
  asset_id bigint primary key,
  agent_id varchar(255) not null unique,
  router_id varchar(255) not null unique,
  updated_by varchar(120) not null,
  updated_at timestamp not null,
  foreign key (asset_id) references assets(id) on delete cascade
);

create table router_control_commands (
  id varchar(36) primary key,
  asset_id bigint not null,
  agent_id varchar(255) not null,
  router_id varchar(255) not null,
  action varchar(40) not null,
  payload_json mediumtext not null,
  status varchar(40) not null,
  result_json mediumtext,
  error_message text,
  created_by varchar(120) not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  expires_at timestamp not null,
  foreign key (asset_id) references assets(id) on delete cascade
);
create index idx_router_commands_asset on router_control_commands(asset_id, created_at);
create index idx_router_commands_status on router_control_commands(status, expires_at);
