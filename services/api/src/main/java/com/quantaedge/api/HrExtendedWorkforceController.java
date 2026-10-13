package com.quantaedge.api;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Extended HR workflows. Employee records always use hr_employee; this controller never
 * creates an alternate employee registry or silently changes employment status on exit.
 */
@RestController
@RequestMapping("/api/v1/hr")
public class HrExtendedWorkforceController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService audit;

  public HrExtendedWorkforceController(JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService audit) {
    this.jdbc=jdbc; this.authorization=authorization; this.audit=audit;
  }

  // Recruitment
  @GetMapping("/recruitment/vacancies")
  public List<Map<String,Object>> vacancies(@RequestParam long organizationId,
      @RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_RECRUITMENT_VIEW"); organization(organizationId);
    return jdbc.queryForList("select * from hr_vacancy where organization_id=? order by created_at desc,id desc",organizationId);
  }

  @PostMapping("/recruitment/vacancies") @Transactional
  public Map<String,Object> createVacancy(@RequestBody Map<String,Object> b,
      @RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_RECRUITMENT_MANAGE");
    long org=id(b.get("organizationId"),"Organisation"); organization(org);
    String title=required(b.get("title"),160,"Title"),description=required(b.get("description"),12000,"Job description");
    int openings=integer(b.getOrDefault("openings",1),1,10000,"Openings");
    Long dept=optionalId(b.get("departmentId"));
    if(dept!=null && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_department where id=? and organization_id=? and active)",Boolean.class,dept,org))) throw bad("Department must be active and belong to this organisation.");
    String type=optional(b.get("employmentTypeCode"),32);
    if(type!=null && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_employment_type where code=? and active)",Boolean.class,type.toUpperCase()))) throw bad("Choose an active employment type.");
    Long key=jdbc.queryForObject("insert into hr_vacancy(organization_id,title,department_id,description,employment_type_code,openings,created_by_staff_id) values (?,?,?,?,?,?,?) returning id",Long.class,org,title,dept,description,type==null?null:type.toUpperCase(),openings,c.staffId());
    event(org,"VACANCY",key,"CREATED",c,null,"{}"); record(c,"/api/v1/hr/recruitment/vacancies/"+key,"Created recruitment vacancy");
    return one("select * from hr_vacancy where id=?",key);
  }

  @PatchMapping("/recruitment/vacancies/{id}/status") @Transactional
  public Map<String,Object> vacancyStatus(@PathVariable long id,@RequestBody Map<String,Object> b,
      @RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_RECRUITMENT_MANAGE"); Map<String,Object> v=one("select * from hr_vacancy where id=?",id);
    String next=required(b.get("status"),20,"Status").toUpperCase();
    if(!List.of("DRAFT","OPEN","ON_HOLD","CLOSED","CANCELLED").contains(next)) throw bad("Unsupported vacancy status.");
    if("OPEN".equals(next)&&"DRAFT".equals(v.get("vacancy_status"))) jdbc.update("update hr_vacancy set vacancy_status=?,published_at=now(),updated_at=now() where id=?",next,id);
    else jdbc.update("update hr_vacancy set vacancy_status=?,updated_at=now() where id=?",next,id);
    event(((Number)v.get("organization_id")).longValue(),"VACANCY",id,"STATUS_CHANGED",c,optional(b.get("reason"),1000),"{\"status\":\""+next+"\"}");
    return one("select * from hr_vacancy where id=?",id);
  }

  @GetMapping("/recruitment/candidates")
  public List<Map<String,Object>> candidates(@RequestParam long organizationId,
      @RequestParam(required=false) String stage,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_RECRUITMENT_VIEW"); organization(organizationId);
    if(stage==null||stage.isBlank()) return jdbc.queryForList("select c.*,v.title vacancy_title from hr_candidate c left join hr_vacancy v on v.id=c.vacancy_id where c.organization_id=? order by c.created_at desc,c.id desc",organizationId);
    String s=stage.toUpperCase(); if(!List.of("APPLIED","SCREENING","INTERVIEW","OFFER","HIRED","REJECTED","WITHDRAWN").contains(s)) throw bad("Unsupported recruitment stage.");
    return jdbc.queryForList("select c.*,v.title vacancy_title from hr_candidate c left join hr_vacancy v on v.id=c.vacancy_id where c.organization_id=? and c.pipeline_stage=? order by c.created_at desc,c.id desc",organizationId,s);
  }

  @PostMapping("/recruitment/candidates") @Transactional
  public Map<String,Object> createCandidate(@RequestBody Map<String,Object> b,
      @RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_RECRUITMENT_MANAGE"); long org=id(b.get("organizationId"),"Organisation"); organization(org);
    Long vacancy=optionalId(b.get("vacancyId"));
    if(vacancy!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_vacancy where id=? and organization_id=? and vacancy_status='OPEN')",Boolean.class,vacancy,org))) throw bad("Select an open vacancy in this organisation.");
    String name=required(b.get("fullName"),160,"Candidate name"),email=optional(b.get("email"),254),phone=optional(b.get("phoneE164"),24);
    if(email==null&&phone==null) throw bad("Provide at least one candidate contact method.");
    Long key=jdbc.queryForObject("insert into hr_candidate(organization_id,vacancy_id,full_name,email,phone_e164,resume_document_ref,application_notes,created_by_staff_id,updated_by_staff_id) values (?,?,?,?,?,?,?,?,?) returning id",Long.class,org,vacancy,name,email,phone,optional(b.get("resumeDocumentRef"),500),optional(b.get("notes"),4000),c.staffId(),c.staffId());
    event(org,"CANDIDATE",key,"APPLIED",c,null,"{}"); record(c,"/api/v1/hr/recruitment/candidates/"+key,"Created candidate record");
    return one("select * from hr_candidate where id=?",key);
  }

  @PatchMapping("/recruitment/candidates/{id}/stage") @Transactional
  public Map<String,Object> candidateStage(@PathVariable long id,@RequestBody Map<String,Object> b,
      @RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_RECRUITMENT_MANAGE"); Map<String,Object> old=one("select * from hr_candidate where id=?",id);
    String next=required(b.get("stage"),24,"Stage").toUpperCase();
    if(!List.of("APPLIED","SCREENING","INTERVIEW","OFFER","HIRED","REJECTED","WITHDRAWN").contains(next)) throw bad("Unsupported candidate stage.");
    if(List.of("HIRED","REJECTED","WITHDRAWN").contains(String.valueOf(old.get("pipeline_stage")))&&!next.equals(old.get("pipeline_stage"))) throw conflict("A terminal candidate stage cannot be changed; create an auditable correction workflow.");
    if("HIRED".equals(next)) throw bad("Use the hiring-decision and conversion workflow; a stage change alone cannot mark a candidate hired.");
    jdbc.update("update hr_candidate set pipeline_stage=?,updated_by_staff_id=?,updated_at=now() where id=?",next,c.staffId(),id);
    event(((Number)old.get("organization_id")).longValue(),"CANDIDATE",id,"STAGE_CHANGED",c,optional(b.get("reason"),1000),"{\"from\":\""+old.get("pipeline_stage")+"\",\"to\":\""+next+"\"}");
    return one("select * from hr_candidate where id=?",id);
  }

  @PostMapping("/recruitment/candidates/{id}/evaluations") @Transactional
  public Map<String,Object> evaluateCandidate(@PathVariable long id,@RequestBody Map<String,Object> b,
      @RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_RECRUITMENT_MANAGE"); Map<String,Object> candidate=one("select * from hr_candidate where id=?",id);
    String type=required(b.get("evaluationType"),24,"Evaluation type").toUpperCase();
    if(!List.of("INTERVIEW","ASSESSMENT","REFERENCE","HIRING_DECISION","NOTE").contains(type)) throw bad("Unsupported evaluation type.");
    java.math.BigDecimal score=null; if(b.get("score")!=null) try { score=new java.math.BigDecimal(String.valueOf(b.get("score"))); if(score.scale()>2||score.abs().compareTo(new java.math.BigDecimal("100000"))>0) throw new NumberFormatException(); } catch(RuntimeException ex) { throw bad("Score must be a valid number with at most two decimal places."); }
    Long key=jdbc.queryForObject("insert into hr_candidate_evaluation(candidate_id,evaluation_type,scheduled_at,evaluator_staff_id,score,outcome,notes) values (?,?,?::timestamptz,?,?,?,?) returning id",Long.class,id,type,optional(b.get("scheduledAt"),40),c.staffId(),score,optional(b.get("outcome"),24),optional(b.get("notes"),4000));
    if("HIRING_DECISION".equals(type)) {
      String decision=required(b.get("outcome"),20,"Hiring decision").toUpperCase();
      if(!List.of("APPROVED","DECLINED").contains(decision)) throw bad("Hiring decision must be APPROVED or DECLINED.");
      jdbc.update("update hr_candidate set hiring_decision=?,pipeline_stage=?,updated_by_staff_id=?,updated_at=now() where id=?",decision,"APPROVED".equals(decision)?"OFFER":"REJECTED",c.staffId(),id);
    }
    event(((Number)candidate.get("organization_id")).longValue(),"CANDIDATE",id,"EVALUATED",c,optional(b.get("notes"),1000),"{\"evaluationId\":"+key+",\"type\":\""+type+"\"}");
    return one("select * from hr_candidate_evaluation where id=?",key);
  }

  /** Converts an approved offer into the canonical employee row, in one transaction. */
  @PostMapping("/recruitment/candidates/{id}/convert") @Transactional
  public Map<String,Object> convertCandidate(@PathVariable long id,@RequestBody Map<String,Object> b,
      @RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_RECRUITMENT_MANAGE"); Map<String,Object> candidate=one("select * from hr_candidate where id=? for update",id);
    if(!"APPROVED".equals(candidate.get("hiring_decision"))||candidate.get("converted_employee_id")!=null) throw conflict("Only an approved, not-yet-converted candidate can be onboarded.");
    long org=((Number)candidate.get("organization_id")).longValue();
    String type=required(b.get("employmentTypeCode"),32,"Employment type").toUpperCase();
    if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_employment_type where code=? and active)",Boolean.class,type))) throw bad("Choose an active employment type.");
    LocalDate joining=date(required(b.get("joiningDate"),10,"Joining date"),"Joining date");
    Long dept=optionalId(b.get("departmentId")),designation=optionalId(b.get("designationId")),manager=optionalId(b.get("managerEmployeeId"));
    if(dept!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_department where id=? and organization_id=? and active)",Boolean.class,dept,org))) throw bad("Department must be active in this organisation.");
    if(designation!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_designation where id=? and organization_id=? and active)",Boolean.class,designation,org))) throw bad("Designation must be active in this organisation.");
    if(manager!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_employee where id=? and organization_id=? and employment_status='ACTIVE')",Boolean.class,manager,org))) throw bad("Manager must be an active employee in this organisation.");
    String number="QE-EMP-"+String.format("%06d",jdbc.queryForObject("select nextval('hr_employee_number_seq')",Long.class));
    Long employeeId=jdbc.queryForObject("insert into hr_employee(public_id,employee_number,organization_id,department_id,designation_id,employment_type_code,manager_employee_id,display_name,email,phone_e164,joining_date,employment_status,notes,created_by_staff_id,updated_by_staff_id) values (?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?) returning id",Long.class,UUID.randomUUID(),number,org,dept,designation,type,manager,candidate.get("full_name"),candidate.get("email"),candidate.get("phone_e164"),Date.valueOf(joining),optional(b.get("notes"),1000),c.staffId(),c.staffId());
    jdbc.update("insert into hr_employee_history(employee_id,changed_by_staff_id,change_type,effective_date,after_state,reason) values (?,?,'EMPLOYEE_CREATED_FROM_RECRUITMENT',?,jsonb_build_object('employeeNumber',?,'displayName',?,'organizationId',?),?)",employeeId,c.staffId(),Date.valueOf(joining),number,candidate.get("full_name"),org,"Candidate "+id+" hired");
    jdbc.update("update hr_candidate set pipeline_stage='HIRED',converted_employee_id=?,updated_by_staff_id=?,updated_at=now() where id=?",employeeId,c.staffId(),id);
    event(org,"CANDIDATE",id,"CONVERTED_TO_EMPLOYEE",c,optional(b.get("reason"),1000),"{\"employeeId\":"+employeeId+"}");
    record(c,"/api/v1/hr/recruitment/candidates/"+id+"/convert","Converted candidate to employee "+number);
    return one("select id,public_id,employee_number,organization_id,display_name,email,phone_e164,joining_date,employment_status from hr_employee where id=?",employeeId);
  }

  // Performance: schema is organisation-configurable JSON; ratings are optional, not hard-coded.
  @GetMapping("/performance/cycles")
  public List<Map<String,Object>> cycles(@RequestParam long organizationId,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_PERFORMANCE_VIEW"); organization(organizationId);
    return jdbc.queryForList("select * from hr_performance_cycle where organization_id=? order by start_date desc,id desc",organizationId);
  }
  @PostMapping("/performance/cycles") @Transactional
  public Map<String,Object> createCycle(@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_PERFORMANCE_MANAGE"); long org=id(b.get("organizationId"),"Organisation"); organization(org);
    LocalDate start=date(required(b.get("startDate"),10,"Start date"),"Start date"),end=date(required(b.get("endDate"),10,"End date"),"End date");
    if(end.isBefore(start)) throw bad("Cycle end must be on or after its start.");
    String schema=optional(b.get("evaluationSchema"),12000);
    Long key=jdbc.queryForObject("insert into hr_performance_cycle(organization_id,name,start_date,end_date,due_date,evaluation_schema,created_by_staff_id) values (?,?,?,?,?,coalesce(?::jsonb,'{\"fields\":[{\"key\":\"achievements\",\"label\":\"Achievements\",\"type\":\"text\",\"required\":true}]}'::jsonb),?) returning id",Long.class,org,required(b.get("name"),160,"Cycle name"),Date.valueOf(start),Date.valueOf(end),b.get("dueDate")==null?null:Date.valueOf(date(String.valueOf(b.get("dueDate")),"Due date")),schema,c.staffId());
    event(org,"PERFORMANCE_CYCLE",key,"CREATED",c,null,"{}"); return one("select * from hr_performance_cycle where id=?",key);
  }
  @PatchMapping("/performance/cycles/{id}/status") @Transactional
  public Map<String,Object> cycleStatus(@PathVariable long id,@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_PERFORMANCE_MANAGE"); Map<String,Object> old=one("select * from hr_performance_cycle where id=?",id); String next=required(b.get("status"),20,"Status").toUpperCase();
    if(!List.of("DRAFT","OPEN","IN_REVIEW","CLOSED","CANCELLED").contains(next)) throw bad("Unsupported performance cycle status.");
    String current=String.valueOf(old.get("cycle_status"));
    if(!Map.of("DRAFT",List.of("OPEN","CANCELLED"),"OPEN",List.of("IN_REVIEW","CANCELLED"),"IN_REVIEW",List.of("OPEN","CLOSED"),"CLOSED",List.of(),"CANCELLED",List.of()).get(current).contains(next)) throw conflict("Invalid performance cycle transition.");
    jdbc.update("update hr_performance_cycle set cycle_status=?,updated_at=now() where id=?",next,id);
    event(((Number)old.get("organization_id")).longValue(),"PERFORMANCE_CYCLE",id,"STATUS_CHANGED",c,optional(b.get("reason"),1000),"{\"status\":\""+next+"\"}");
    return one("select * from hr_performance_cycle where id=?",id);
  }
  @PostMapping("/performance/cycles/{cycleId}/goals") @Transactional
  public Map<String,Object> createGoal(@PathVariable long cycleId,@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_PERFORMANCE_MANAGE"); Map<String,Object> cycle=one("select * from hr_performance_cycle where id=?",cycleId);
    long employee=id(b.get("employeeId"),"Employee"); employeeInOrg(employee,((Number)cycle.get("organization_id")).longValue());
    Long key=jdbc.queryForObject("insert into hr_employee_goal(cycle_id,employee_id,title,description,success_measure,target_date,created_by_staff_id) values (?,?,?,?,?,?,?) returning id",Long.class,cycleId,employee,required(b.get("title"),200,"Goal title"),optional(b.get("description"),4000),optional(b.get("successMeasure"),2000),b.get("targetDate")==null?null:Date.valueOf(date(String.valueOf(b.get("targetDate")),"Target date")),c.staffId());
    return one("select * from hr_employee_goal where id=?",key);
  }
  @GetMapping("/performance/reviews")
  public List<Map<String,Object>> reviews(@RequestParam long organizationId,@RequestParam(required=false) Long employeeId,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_PERFORMANCE_VIEW"); organization(organizationId);
    if(employeeId!=null) employeeInOrg(employeeId,organizationId);
    return employeeId==null?jdbc.queryForList("select r.*,e.display_name employee_name,e.employee_number,c.name cycle_name from hr_performance_review r join hr_employee e on e.id=r.employee_id join hr_performance_cycle c on c.id=r.cycle_id where c.organization_id=? order by r.created_at desc,r.id desc",organizationId):jdbc.queryForList("select r.*,e.display_name employee_name,e.employee_number,c.name cycle_name from hr_performance_review r join hr_employee e on e.id=r.employee_id join hr_performance_cycle c on c.id=r.cycle_id where c.organization_id=? and r.employee_id=? order by r.created_at desc,r.id desc",organizationId,employeeId);
  }
  @PostMapping("/performance/reviews") @Transactional
  public Map<String,Object> createReview(@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_PERFORMANCE_MANAGE"); long cycle=id(b.get("cycleId"),"Cycle"),employee=id(b.get("employeeId"),"Employee");
    Map<String,Object> cy=one("select * from hr_performance_cycle where id=?",cycle); employeeInOrg(employee,((Number)cy.get("organization_id")).longValue());
    if(!"OPEN".equals(cy.get("cycle_status"))) throw conflict("Reviews can only be created in an open cycle.");
    Long reviewer=optionalId(b.get("reviewerEmployeeId")); if(reviewer!=null) employeeInOrg(reviewer,((Number)cy.get("organization_id")).longValue());
    Long key=jdbc.queryForObject("insert into hr_performance_review(cycle_id,employee_id,reviewer_employee_id,created_by_staff_id) values (?,?,?,?) returning id",Long.class,cycle,employee,reviewer,c.staffId());
    return one("select * from hr_performance_review where id=?",key);
  }
  @PatchMapping("/performance/reviews/{id}") @Transactional
  public Map<String,Object> updateReview(@PathVariable long id,@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_PERFORMANCE_MANAGE"); Map<String,Object> old=one("select * from hr_performance_review where id=? for update",id);
    String status=String.valueOf(old.get("review_status")),action=required(b.get("action"),20,"Action").toUpperCase();
    if("SUBMIT".equals(action)&&"DRAFT".equals(status)) {
      String self=optional(b.get("selfAssessment"),16000); if(self==null) self="{}";
      jdbc.update("update hr_performance_review set self_assessment=?::jsonb,review_status='SUBMITTED',submitted_at=now(),updated_at=now() where id=?",self,id);
    } else if("APPROVE".equals(action)&&"SUBMITTED".equals(status)) {
      String manager=optional(b.get("managerAssessment"),16000); if(manager==null) manager="{}";
      java.math.BigDecimal rating=null; if(b.get("rating")!=null) try { rating=new java.math.BigDecimal(String.valueOf(b.get("rating"))); if(rating.scale()>2||rating.signum()<0) throw new NumberFormatException(); } catch(RuntimeException ex) { throw bad("Rating must be a non-negative decimal with at most two places."); }
      jdbc.update("update hr_performance_review set manager_assessment=?::jsonb,rating=?,review_status='APPROVED',approved_by_staff_id=?,approved_at=now(),updated_at=now() where id=?",manager,rating,c.staffId(),id);
    } else if("RETURN".equals(action)&&List.of("SUBMITTED","APPROVED").contains(status)) {
      jdbc.update("update hr_performance_review set review_status='RETURNED',updated_at=now() where id=?",id);
    } else throw conflict("Review action is not valid for its current state.");
    event(((Number)old.get("cycle_id")).longValue(),"PERFORMANCE_REVIEW",id,"ACTION_"+action,c,optional(b.get("reason"),1000),"{}");
    return one("select * from hr_performance_review where id=?",id);
  }

  // Assets
  @GetMapping("/assets")
  public List<Map<String,Object>> assets(@RequestParam long organizationId,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_ASSET_VIEW"); organization(organizationId);
    return jdbc.queryForList("select a.*,x.id assignment_id,x.employee_id,x.assigned_at,x.returned_at,x.condition_on_assignment,x.condition_on_return,e.display_name employee_name,e.employee_number from hr_asset a left join hr_asset_assignment x on x.asset_id=a.id and x.returned_at is null left join hr_employee e on e.id=x.employee_id where a.organization_id=? order by a.asset_status,a.asset_tag",organizationId);
  }
  @PostMapping("/assets") @Transactional
  public Map<String,Object> createAsset(@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_ASSET_MANAGE"); long org=id(b.get("organizationId"),"Organisation"); organization(org);
    Long key=jdbc.queryForObject("insert into hr_asset(organization_id,asset_tag,asset_type,description,serial_number,acquired_on,created_by_staff_id) values (?,?,?,?,?,?,?) returning id",Long.class,org,required(b.get("assetTag"),80,"Asset tag"),required(b.get("assetType"),80,"Asset type"),optional(b.get("description"),500),optional(b.get("serialNumber"),160),b.get("acquiredOn")==null?null:Date.valueOf(date(String.valueOf(b.get("acquiredOn")),"Acquired date")),c.staffId());
    event(org,"ASSET",key,"CREATED",c,null,"{}"); record(c,"/api/v1/hr/assets/"+key,"Created asset inventory record"); return one("select * from hr_asset where id=?",key);
  }
  @PostMapping("/assets/{id}/assign") @Transactional
  public Map<String,Object> assignAsset(@PathVariable long id,@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_ASSET_MANAGE"); Map<String,Object> asset=one("select * from hr_asset where id=? for update",id);
    if(!"AVAILABLE".equals(asset.get("asset_status"))) throw conflict("Only an available asset can be assigned.");
    long employee=id(b.get("employeeId"),"Employee"); employeeInOrg(employee,((Number)asset.get("organization_id")).longValue());
    String condition=optional(b.get("condition"),20); if(condition==null)condition="GOOD"; condition=condition.toUpperCase();
    if(!List.of("NEW","GOOD","FAIR","DAMAGED","UNKNOWN").contains(condition)) throw bad("Unsupported asset condition.");
    Long key=jdbc.queryForObject("insert into hr_asset_assignment(asset_id,employee_id,assigned_by_staff_id,condition_on_assignment,assignment_note) values (?,?,?,?,?) returning id",Long.class,id,employee,c.staffId(),condition,optional(b.get("note"),1000));
    jdbc.update("update hr_asset set asset_status='ASSIGNED',updated_at=now() where id=?",id);
    event(((Number)asset.get("organization_id")).longValue(),"ASSET",id,"ASSIGNED",c,optional(b.get("note"),1000),"{\"employeeId\":"+employee+",\"assignmentId\":"+key+"}");
    return one("select * from hr_asset_assignment where id=?",key);
  }
  @PostMapping("/assets/assignments/{assignmentId}/return") @Transactional
  public Map<String,Object> returnAsset(@PathVariable long assignmentId,@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_ASSET_MANAGE"); Map<String,Object> a=one("select x.*,t.organization_id from hr_asset_assignment x join hr_asset t on t.id=x.asset_id where x.id=? for update",assignmentId);
    if(a.get("returned_at")!=null) throw conflict("This asset assignment has already been returned.");
    String condition=required(b.get("condition"),20,"Return condition").toUpperCase(); if(!List.of("NEW","GOOD","FAIR","DAMAGED","UNKNOWN").contains(condition)) throw bad("Unsupported return condition.");
    jdbc.update("update hr_asset_assignment set returned_at=now(),returned_by_staff_id=?,condition_on_return=?,return_note=? where id=? and returned_at is null",c.staffId(),condition,optional(b.get("note"),1000),assignmentId);
    String status="DAMAGED".equals(condition)?"MAINTENANCE":"AVAILABLE";
    jdbc.update("update hr_asset set asset_status=?,condition_status=?,updated_at=now() where id=?",status,condition,a.get("asset_id"));
    event(((Number)a.get("organization_id")).longValue(),"ASSET",((Number)a.get("asset_id")).longValue(),"RETURNED",c,optional(b.get("note"),1000),"{\"assignmentId\":"+assignmentId+",\"condition\":\""+condition+"\"}");
    return one("select * from hr_asset_assignment where id=?",assignmentId);
  }

  // Exit: this module records the process and settlement inputs; the canonical employee status
  // is changed only through HrEmployeeController after the authorised clearance workflow.
  @GetMapping("/exits")
  public List<Map<String,Object>> exits(@RequestParam long organizationId,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_EXIT_VIEW"); organization(organizationId);
    return jdbc.queryForList("select x.*,e.employee_number,e.display_name,e.employment_status from hr_employee_exit x join hr_employee e on e.id=x.employee_id where e.organization_id=? order by x.created_at desc,x.id desc",organizationId);
  }
  @PostMapping("/exits") @Transactional
  public Map<String,Object> initiateExit(@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_EXIT_MANAGE"); long employee=id(b.get("employeeId"),"Employee"); Map<String,Object> e=one("select * from hr_employee where id=?",employee);
    if(!List.of("ACTIVE","ON_LEAVE","NOTICE_PERIOD").contains(String.valueOf(e.get("employment_status")))) throw conflict("Only current employees can initiate an exit.");
    String type=required(b.get("exitType"),24,"Exit type").toUpperCase(); if(!List.of("RESIGNATION","RETIREMENT","END_OF_CONTRACT","TERMINATION","OTHER").contains(type)) throw bad("Unsupported exit type.");
    LocalDate last=date(required(b.get("requestedLastWorkingDate"),10,"Requested last working date"),"Requested last working date");
    if(last.isBefore(LocalDate.now())) throw bad("Requested last working date cannot be in the past.");
    int notice=integer(b.getOrDefault("noticePeriodDays",0),0,3650,"Notice period days");
    Long key=jdbc.queryForObject("insert into hr_employee_exit(employee_id,initiated_by_staff_id,exit_type,requested_last_working_date,notice_period_days,reason) values (?,?,?,?,?,?) returning id",Long.class,employee,c.staffId(),type,Date.valueOf(last),notice,optional(b.get("reason"),4000));
    jdbc.update("insert into hr_exit_checklist_item(exit_id,item_key,title,owner_role) values (?,?,?,?),(?,?,?,?),(?,?,?,?),(?,?,?,?)",key,"HANDOVER","Knowledge and work handover","MANAGER",key,"ASSET_RETURN","Return assigned assets","IT",key,"ACCESS_REVIEW","Review access removal","IT",key,"FINAL_SETTLEMENT_INPUTS","Prepare final-settlement inputs","PAYROLL");
    event(((Number)e.get("organization_id")).longValue(),"EXIT",key,"REQUESTED",c,optional(b.get("reason"),1000),"{\"employeeId\":"+employee+"}");
    record(c,"/api/v1/hr/exits/"+key,"Initiated employee exit workflow"); return one("select * from hr_employee_exit where id=?",key);
  }
  @PatchMapping("/exits/{id}/status") @Transactional
  public Map<String,Object> exitStatus(@PathVariable long id,@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_EXIT_MANAGE"); Map<String,Object> old=one("select x.*,e.organization_id from hr_employee_exit x join hr_employee e on e.id=x.employee_id where x.id=? for update",id);
    String current=String.valueOf(old.get("exit_status")),next=required(b.get("status"),24,"Status").toUpperCase();
    if(!Map.of("REQUESTED",List.of("UNDER_REVIEW","CANCELLED"),"UNDER_REVIEW",List.of("APPROVED","CANCELLED"),"APPROVED",List.of("CLEARANCE","CANCELLED"),"CLEARANCE",List.of("READY_FOR_SETTLEMENT"),"READY_FOR_SETTLEMENT",List.of("COMPLETED"),"COMPLETED",List.of(),"CANCELLED",List.of()).getOrDefault(current,List.of()).contains(next)) throw conflict("Invalid exit workflow transition.");
    if("COMPLETED".equals(next)) {
      Long pending=jdbc.queryForObject("select count(*) from hr_exit_checklist_item where exit_id=? and item_status not in ('CLEARED','WAIVED')",Long.class,id);
      if(pending!=null&&pending>0) throw conflict("All exit checklist items must be cleared or waived before completion.");
      if(!"SETTLED".equals(old.get("final_settlement_status"))) throw conflict("Final settlement must be confirmed as settled before exit completion.");
    }
    LocalDate approved=old.get("approved_last_working_date") instanceof Date d?d.toLocalDate():null;
    if(b.get("approvedLastWorkingDate")!=null) approved=date(String.valueOf(b.get("approvedLastWorkingDate")),"Approved last working date");
    jdbc.update("update hr_employee_exit set exit_status=?,approved_last_working_date=coalesce(?,approved_last_working_date),hr_notes=coalesce(?,hr_notes),completed_at=case when ?='COMPLETED' then now() else completed_at end,updated_at=now() where id=?",next,approved==null?null:Date.valueOf(approved),optional(b.get("notes"),4000),next,id);
    event(((Number)old.get("organization_id")).longValue(),"EXIT",id,"STATUS_"+next,c,optional(b.get("reason"),1000),"{}");
    return one("select * from hr_employee_exit where id=?",id);
  }
  @PatchMapping("/exits/{id}/checklist/{itemId}") @Transactional
  public Map<String,Object> checklist(@PathVariable long id,@PathVariable long itemId,@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_EXIT_MANAGE"); Map<String,Object> exit=one("select x.id,e.organization_id from hr_employee_exit x join hr_employee e on e.id=x.employee_id where x.id=?",id);
    String status=required(b.get("status"),16,"Checklist status").toUpperCase(); if(!List.of("PENDING","IN_PROGRESS","CLEARED","WAIVED").contains(status)) throw bad("Unsupported checklist status.");
    int n=jdbc.update("update hr_exit_checklist_item set item_status=?,notes=?,completed_by_staff_id=case when ? in ('CLEARED','WAIVED') then ? else null end,completed_at=case when ? in ('CLEARED','WAIVED') then now() else null end where id=? and exit_id=?",status,optional(b.get("notes"),1000),status,c.staffId(),status,itemId,id);
    if(n!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Exit checklist item not found.");
    event(((Number)exit.get("organization_id")).longValue(),"EXIT",id,"CHECKLIST_"+status,c,optional(b.get("notes"),1000),"{\"itemId\":"+itemId+"}");
    return one("select * from hr_exit_checklist_item where id=?",itemId);
  }
  @PatchMapping("/exits/{id}/settlement") @Transactional
  public Map<String,Object> settlement(@PathVariable long id,@RequestBody Map<String,Object> b,@RequestAttribute(value="authContext",required=false) AuthContext c) {
    require(c,"HR_EXIT_MANAGE"); Map<String,Object> old=one("select x.*,e.organization_id from hr_employee_exit x join hr_employee e on e.id=x.employee_id where x.id=? for update",id);
    String status=required(b.get("status"),24,"Settlement status").toUpperCase(); if(!List.of("NOT_STARTED","INPUTS_READY","IN_PROGRESS","SETTLED","ON_HOLD").contains(status)) throw bad("Unsupported settlement status.");
    if("SETTLED".equals(status)&&!"READY_FOR_SETTLEMENT".equals(old.get("exit_status"))) throw conflict("Exit clearance must be ready before final settlement can be marked settled.");
    jdbc.update("update hr_employee_exit set final_settlement_status=?,updated_at=now() where id=?",status,id);
    event(((Number)old.get("organization_id")).longValue(),"EXIT",id,"SETTLEMENT_"+status,c,optional(b.get("reason"),1000),"{}");
    return one("select * from hr_employee_exit where id=?",id);
  }

  private void require(AuthContext c,String p){authorization.requirePermission(c,p);}
  private void organization(long id){if(id<=0||!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_organization where id=? and active=true)",Boolean.class,id)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active organisation not found.");}
  private void employeeInOrg(long employee,long org){if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_employee where id=? and organization_id=?)",Boolean.class,employee,org)))throw bad("Employee must belong to this organisation.");}
  private Map<String,Object> one(String sql,Object... args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"HR record not found.");return rows.getFirst();}
  private long id(Object v,String label){try{long n=Long.parseLong(String.valueOf(v));if(n>0)return n;}catch(RuntimeException ignored){}throw bad(label+" must be a positive integer.");}
  private Long optionalId(Object v){if(v==null||String.valueOf(v).isBlank())return null;return id(v,"Related record");}
  private int integer(Object v,int min,int max,String label){try{int n=Integer.parseInt(String.valueOf(v));if(n>=min&&n<=max)return n;}catch(RuntimeException ignored){}throw bad(label+" must be between "+min+" and "+max+".");}
  private String required(Object v,int max,String label){if(!(v instanceof String s)||s.trim().isEmpty()||s.trim().length()>max)throw bad(label+" is required and must be no longer than "+max+" characters.");return s.trim();}
  private String optional(Object v,int max){if(v==null)return null;String s=String.valueOf(v).trim();if(s.isEmpty())return null;if(s.length()>max)throw bad("A text field exceeds its maximum length of "+max+" characters.");return s;}
  private LocalDate date(String s,String label){try{return LocalDate.parse(s);}catch(RuntimeException ex){throw bad(label+" must use YYYY-MM-DD format.");}}
  private void event(long org,String type,long id,String action,AuthContext c,String reason,String details){jdbc.update("insert into hr_extended_event(organization_id,entity_type,entity_id,event_type,actor_staff_id,reason,details) values (?,?,?,?,?,?,?::jsonb)",org,type,id,action,c.staffId(),reason,details==null?"{}":details);}
  private void record(AuthContext c,String path,String detail){audit.recordAction(c,path,detail);}
  private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
  private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
}
