insert into staff_permission_catalog(permission_key,display_name,description) values
 ('HR_MANAGER_TEAM_VIEW','View reporting employees in People Operations','Restricts HR list and employee-specific operations to the manager and their reporting hierarchy when combined with existing HR permissions.')
on conflict(permission_key) do update set display_name=excluded.display_name,description=excluded.description;
