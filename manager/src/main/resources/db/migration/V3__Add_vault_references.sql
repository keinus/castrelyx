alter table integration_configs add column vault_ref varchar(500);

alter table snmp_credentials add column vault_ref varchar(500);
