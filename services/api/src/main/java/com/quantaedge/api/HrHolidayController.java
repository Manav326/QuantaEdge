package com.quantaedge.api;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/hr/organization/{organizationId}/holidays")
public class HrHolidayController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService audit;

  public HrHolidayController(JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService audit) {
    this.jdbc=jdbc; this.authorization=authorization; this.audit=audit;
  }

  @GetMapping
  public List<Map<String,Object>> list(@PathVariable long organizationId,
      @RequestParam(required=false) String from,@RequestParam(required=false) String to,
      @RequestParam(defaultValue="true") boolean activeOnly,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_HOLIDAY_VIEW");
    ensureOrganization(organizationId);
    LocalDate start=from==null||from.isBlank()?null:date(from);
    LocalDate end=to==null||to.isBlank()?null:date(to);
    if(start!=null&&end!=null&&end.isBefore(start)) throw bad("End date cannot be before start date.");
    if(start!=null&&end!=null&&java.time.temporal.ChronoUnit.DAYS.between(start,end)>731) throw bad("Holiday calendar range cannot exceed two years.");
    return jdbc.queryForList("select id,organization_id,holiday_date,name,description,region,active,created_at,updated_at from hr_holiday_calendar where organization_id=? and (?=false or active=true) and (?::date is null or holiday_date>=?) and (?::date is null or holiday_date<=?) order by holiday_date,name",organizationId,activeOnly,start==null?null:Date.valueOf(start),start==null?null:Date.valueOf(start),end==null?null:Date.valueOf(end),end==null?null:Date.valueOf(end));
  }

  @PostMapping
  @Transactional
  public Map<String,Object> create(@PathVariable long organizationId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_HOLIDAY_MANAGE");
    ensureOrganization(organizationId);
    LocalDate holidayDate=date(String.valueOf(body.getOrDefault("holidayDate","")));
    String name=required(body.get("name"),120,"Holiday name");
    String description=optional(body.get("description"),500);
    String region=optional(body.get("region"),80);
    try {
      Long id=jdbc.queryForObject("insert into hr_holiday_calendar(organization_id,holiday_date,name,description,region,created_by_staff_id,updated_by_staff_id) values (?,?,?,?,?,?,?) returning id",Long.class,organizationId,Date.valueOf(holidayDate),name,description,region,context.staffId(),context.staffId());
      Map<String,Object> row=byId(id);
      audit.recordAction(context,"/api/v1/hr/organization/"+organizationId+"/holidays/"+id,"Added holiday "+name+" on "+holidayDate);
      return row;
    } catch(DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"An active holiday already exists for this organisation and date."); }
  }

  @DeleteMapping("/{holidayId}")
  @Transactional
  public Map<String,Object> deactivate(@PathVariable long organizationId,@PathVariable long holidayId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_HOLIDAY_MANAGE");
    int changed=jdbc.update("update hr_holiday_calendar set active=false,updated_by_staff_id=?,updated_at=now() where id=? and organization_id=? and active=true",context.staffId(),holidayId,organizationId);
    if(changed!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active holiday not found.");
    Map<String,Object> row=byId(holidayId);
    audit.recordAction(context,"/api/v1/hr/organization/"+organizationId+"/holidays/"+holidayId,"Deactivated holiday "+row.get("name")+" on "+row.get("holiday_date"));
    return row;
  }

  private void ensureOrganization(long id) {
    if(id<=0||!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_organization where id=? and active=true)",Boolean.class,id))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active organisation not found.");
  }
  private Map<String,Object> byId(long id) { return jdbc.queryForMap("select id,organization_id,holiday_date,name,description,region,active,created_at,updated_at from hr_holiday_calendar where id=?",id); }
  private LocalDate date(String value) { try { return LocalDate.parse(value); } catch(DateTimeParseException|NullPointerException ex) { throw bad("Holiday date must use YYYY-MM-DD format."); } }
  private String required(Object value,int max,String label) { if(!(value instanceof String s)||s.trim().isEmpty()||s.trim().length()>max) throw bad(label+" is required and must be no longer than "+max+" characters."); return s.trim(); }
  private String optional(Object value,int max) { if(value==null)return null; String s=String.valueOf(value).trim(); if(s.isEmpty())return null; if(s.length()>max)throw bad("A text field exceeds its maximum length of "+max+" characters."); return s; }
  private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
