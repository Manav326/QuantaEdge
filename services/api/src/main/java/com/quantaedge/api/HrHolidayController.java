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
    return jdbc.queryForList("select id,organization_id,holiday_date,name,description,region,calendar_source,source_url,jurisdiction,calendar_year,source_verified_at,source_verified_by_staff_id,active,created_at,updated_at from hr_holiday_calendar where organization_id=? and (?=false or active=true) and (?::date is null or holiday_date>=?) and (?::date is null or holiday_date<=?) order by holiday_date,name",organizationId,activeOnly,start==null?null:Date.valueOf(start),start==null?null:Date.valueOf(start),end==null?null:Date.valueOf(end),end==null?null:Date.valueOf(end));
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

  @PostMapping("/import")
  @Transactional
  public Map<String,Object> importOfficialCalendar(@PathVariable long organizationId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_HOLIDAY_MANAGE");
    ensureOrganization(organizationId);
    String source=required(body.get("calendarSource"),24,"Calendar source").toUpperCase();
    if(!List.of("CENTRAL_GOVERNMENT","STATE_GOVERNMENT","DISTRICT","OTHER_OFFICIAL").contains(source))
      throw bad("Choose an official central, state, district or other government calendar source.");
    String sourceUrl=required(body.get("sourceUrl"),1000,"Official source URL");
    java.net.URI uri;
    try { uri=java.net.URI.create(sourceUrl); }
    catch(RuntimeException ex) { throw bad("Provide a valid HTTPS official source URL."); }
    String host=uri.getHost()==null?"":uri.getHost().toLowerCase(java.util.Locale.ROOT);
    if(!"https".equalsIgnoreCase(uri.getScheme())||
       !(host.equals("india.gov.in")||host.endsWith(".gov.in")||host.equals("nic.in")||host.endsWith(".nic.in")))
      throw bad("Calendar source must be an HTTPS Government of India/state/district domain (*.gov.in or *.nic.in).");
    String jurisdiction=required(body.get("jurisdiction"),120,"Jurisdiction");
    int year;
    try { year=Integer.parseInt(String.valueOf(body.get("calendarYear"))); }
    catch(RuntimeException ex) { throw bad("Calendar year must be a valid year."); }
    if(year<2000||year>2200)throw bad("Calendar year is outside the supported range.");
    if(!(body.get("holidays") instanceof List<?> holidays)||holidays.isEmpty()||holidays.size()>200)
      throw bad("Provide between 1 and 200 holiday entries.");
    java.util.Set<LocalDate> seen=new java.util.HashSet<>();
    for(Object value:holidays) {
      if(!(value instanceof Map<?,?> item))throw bad("Each holiday entry must be an object.");
      LocalDate holidayDate=date(String.valueOf(item.get("holidayDate")));
      if(holidayDate.getYear()!=year)throw bad("Every holiday date must belong to the selected calendar year.");
      if(!seen.add(holidayDate))throw bad("A calendar import cannot contain duplicate dates.");
      String name=required(item.get("name"),120,"Holiday name");
      String description=optional(item.get("description"),500);
      String region=optional(item.get("region"),80);
      try {
        jdbc.update("""
          insert into hr_holiday_calendar(organization_id,holiday_date,name,description,region,
            calendar_source,source_url,jurisdiction,calendar_year,source_verified_at,source_verified_by_staff_id,
            created_by_staff_id,updated_by_staff_id)
          values (?,?,?,?,?,?,?,?,?,now(),?,?,?)
          """,organizationId,Date.valueOf(holidayDate),name,description,region,source,sourceUrl,
            jurisdiction,year,context.staffId(),context.staffId(),context.staffId());
      } catch(DuplicateKeyException ex) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The import conflicts with an existing active holiday on "+holidayDate+". Resolve the organization override before importing again.");
      }
    }
    audit.recordAction(context,"/api/v1/hr/organization/"+organizationId+"/holidays/import",
      "Imported "+holidays.size()+" holiday dates for "+jurisdiction+" "+year+" from "+sourceUrl+"; source verified by staff.");
    return Map.of("organizationId",organizationId,"jurisdiction",jurisdiction,"calendarYear",year,
      "calendarSource",source,"sourceUrl",sourceUrl,"importedCount",holidays.size(),
      "sourceVerifiedAt",java.time.OffsetDateTime.now().toString());
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
  private Map<String,Object> byId(long id) { return jdbc.queryForMap("select id,organization_id,holiday_date,name,description,region,calendar_source,source_url,jurisdiction,calendar_year,source_verified_at,source_verified_by_staff_id,active,created_at,updated_at from hr_holiday_calendar where id=?",id); }
  private LocalDate date(String value) { try { return LocalDate.parse(value); } catch(DateTimeParseException|NullPointerException ex) { throw bad("Holiday date must use YYYY-MM-DD format."); } }
  private String required(Object value,int max,String label) { if(!(value instanceof String s)||s.trim().isEmpty()||s.trim().length()>max) throw bad(label+" is required and must be no longer than "+max+" characters."); return s.trim(); }
  private String optional(Object value,int max) { if(value==null)return null; String s=String.valueOf(value).trim(); if(s.isEmpty())return null; if(s.length()>max)throw bad("A text field exceeds its maximum length of "+max+" characters."); return s; }
  private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
