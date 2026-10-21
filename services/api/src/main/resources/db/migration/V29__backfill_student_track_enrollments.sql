-- V29: enroll inactive legacy profiles as well, so future admin reactivation retains access.
-- Do not change V28: deployed databases may already have its original checksum.
insert into student_track_enrollment(student_id, subject_id, status)
select st.id, s.id, 'ACTIVE'
from student st
join curriculum_class c on c.code=st.class_code and c.active=true
join curriculum_subject s on s.class_id=c.id and s.active=true
on conflict(student_id, subject_id) do nothing;

insert into app_metadata(key,value)
values ('schema','student-track-backfill-v29')
on conflict(key) do update set value=excluded.value;
