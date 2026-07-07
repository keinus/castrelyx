create table remote_access_sessions (
  id varchar(64) primary key,
  asset_id bigint,
  asset_uid varchar(120),
  agent_id varchar(255) not null,
  ssh_user varchar(120) not null,
  target_host varchar(255) not null,
  target_port int not null default 22,
  status varchar(40) not null,
  public_key text not null,
  public_key_fingerprint varchar(160) not null,
  encrypted_private_key text not null,
  authorization_task_id varchar(80),
  revoke_task_id varchar(80),
  created_by bigint,
  created_by_username varchar(120),
  created_at timestamp not null,
  expires_at timestamp not null,
  connected_at timestamp null,
  closed_at timestamp null,
  close_reason varchar(500),
  last_error text,
  foreign key (asset_id) references assets(id)
);

create index idx_remote_access_sessions_agent on remote_access_sessions(agent_id, status, expires_at);
create index idx_remote_access_sessions_asset on remote_access_sessions(asset_uid, status);

create table remote_access_audit_events (
  id bigint primary key auto_increment,
  session_id varchar(64),
  asset_id bigint,
  agent_id varchar(255),
  event_type varchar(80) not null,
  message text,
  created_by bigint,
  created_by_username varchar(120),
  created_at timestamp not null,
  foreign key (session_id) references remote_access_sessions(id)
);

create index idx_remote_access_audit_session on remote_access_audit_events(session_id, created_at);
