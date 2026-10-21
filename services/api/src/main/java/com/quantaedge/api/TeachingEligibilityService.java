package com.quantaedge.api;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * One employment/leave eligibility policy shared by HR academic assignments and live-class
 * creation, rescheduling, start and teacher join. Term assignments are date-bounded by employment;
 * individual sessions are additionally blocked by approved leave on that exact session date.
 */
@Component
public class TeachingEligibilityService {
  private final JdbcTemplate jdbc;
  public TeachingEligibilityService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

  public void requireAssignmentEligible(long employeeId, LocalDate assignmentStart, LocalDate assignmentEnd) {
    if(assignmentStart==null||assignmentEnd==null||assignmentEnd.isBefore(assignmentStart))
      throw bad("Teaching assignment dates must be valid.");
    Map<String,Object> employee=employee(employeeId);
    requireAllowedStatus(employee);
    LocalDate joining=requiredDate(employee.get("joining_date"),"joining date");
    LocalDate exit=optionalDate(employee.get("exit_date"));
    if(assignmentStart.isBefore(joining))
      throw conflict("A teaching assignment cannot start before the employee's joining date ("+joining+").");
    if(exit!=null&&assignmentEnd.isAfter(exit))
      throw conflict("The teaching assignment extends beyond the employee's last working date ("+exit+").");
  }

  public void requireSessionEligible(long employeeId, LocalDate sessionDate,
      LocalDate assignmentStart, LocalDate assignmentEnd) {
    if(sessionDate==null||assignmentStart==null||assignmentEnd==null
        ||sessionDate.isBefore(assignmentStart)||sessionDate.isAfter(assignmentEnd))
      throw conflict("The approved teaching assignment must be effective on the live class date.");
    Map<String,Object> employee=employee(employeeId);
    requireAllowedStatus(employee);
    LocalDate joining=requiredDate(employee.get("joining_date"),"joining date");
    LocalDate exit=optionalDate(employee.get("exit_date"));
    if(sessionDate.isBefore(joining))
      throw conflict("The employee is not eligible to teach before their joining date ("+joining+").");
    if(exit!=null&&sessionDate.isAfter(exit))
      throw conflict("The employee is not eligible to teach after their last working date ("+exit+").");
    Boolean leave=jdbc.queryForObject("""
      select exists(select 1 from hr_leave_request
        where employee_id=? and request_status='APPROVED' and start_date<=? and end_date>=?)
      """,Boolean.class,employeeId,Date.valueOf(sessionDate),Date.valueOf(sessionDate));
    if(Boolean.TRUE.equals(leave))
      throw conflict("The assigned teacher has approved leave on "+sessionDate+". Reschedule or choose an eligible teacher.");
  }

  private Map<String,Object> employee(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList(
        "select id,employment_status,joining_date,exit_date from hr_employee where id=?",id);
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Assigned employee not found.");
    return rows.getFirst();
  }

  private void requireAllowedStatus(Map<String,Object> employee) {
    String status=String.valueOf(employee.get("employment_status"));
    if(!List.of("ACTIVE","ON_LEAVE","NOTICE_PERIOD").contains(status))
      throw conflict("The assigned employee is not eligible to teach under the employee lifecycle policy.");
  }

  private LocalDate requiredDate(Object raw,String label) {
    LocalDate date=optionalDate(raw);
    if(date==null)throw conflict("The employee "+label+" is missing. Resolve the employee record before assigning teaching.");
    return date;
  }

  private LocalDate optionalDate(Object raw) {
    if(raw instanceof Date d)return d.toLocalDate();
    if(raw instanceof LocalDate d)return d;
    if(raw instanceof java.util.Date d)return new Date(d.getTime()).toLocalDate();
    if(raw==null)return null;
    try{return LocalDate.parse(String.valueOf(raw).substring(0,10));}
    catch(RuntimeException ex){throw conflict("The employee lifecycle dates are invalid. Review the employee record.");}
  }

  private ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
  }
  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT,message);
  }
}
