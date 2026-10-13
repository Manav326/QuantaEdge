-- V84: reflect the HRMS bootstrap administrator's explicit HR role in the multi-role directory.
insert into staff_additional_role(staff_id, role_code)
select id, 'HR'
from staff_account
where lower(username)='hrms_admin'
on conflict(staff_id, role_code) do nothing;
