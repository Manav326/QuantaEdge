package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates an isolated source-owned draft version of a released course.
 * The database function clones the course graph into new row IDs and leaves all learner/commercial
 * history on its original program/version. This service deliberately does not publish or reassign
 * the new version to any destination institution.
 */
@Service
public class CourseVersionService {
  private final JdbcTemplate jdbc;

  public CourseVersionService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public Map<String,Object> forkReleasedVersion(
      long sourceProgramId,long institutionId,Long actorStaffId,Long actorInstitutionAdminId) {
    if(sourceProgramId<1L || institutionId<1L)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid source course.");

    try {
      Map<String,Object> row=jdbc.queryForMap(
          "select * from qe_fork_course_version(?,?,?,?)",
          sourceProgramId,institutionId,actorStaffId,actorInstitutionAdminId);
      Map<String,Object> result=new LinkedHashMap<>();
      result.put("created",true);
      result.put("sourceCourseId",sourceProgramId);
      result.put("courseId",number(row.get("new_program_id")));
      result.put("contentVersionNo",number(row.get("content_version_no")));
      result.put("releaseId",number(row.get("release_id")));
      result.put("assignmentId",number(row.get("assignment_id")));
      result.put("releaseStatus","DRAFT");
      result.put("courseRelationship","SOURCED");
      result.put("marketplacePublicationRequired",true);
      result.put("message","An isolated draft version was created. The existing published version remains unchanged. Review the new version, submit it for quality review, complete source approval and the full HRMS Marketplace stepper before distributing it.");
      return result;
    } catch(DataAccessException ex) {
      String message=mostSpecificMessage(ex);
      if(message.contains("COURSE_VERSION_SOURCE_NOT_FOUND"))
        throw conflict("The source course is inactive or no longer exists.");
      if(message.contains("COURSE_VERSION_IDENTITY_REQUIRED"))
        throw conflict("This legacy course needs HRMS/source-owner reconciliation before a safe new version can be created.");
      if(message.contains("COURSE_VERSION_SOURCE_OWNERSHIP"))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Only the HRMS-bound source institution can create a new version of this course.");
      if(message.contains("COURSE_VERSION_RELEASE_REQUIRED"))
        throw conflict("This course has not entered the release workflow yet. Edit its existing draft instead of forking a version.");
      if(message.contains("COURSE_VERSION_DRAFT_EXISTS"))
        throw conflict("A draft version already exists for this course. Open that draft instead of creating another copy.");
      if(message.contains("COURSE_VERSION_SOURCE_STALE"))
        throw conflict("This source version has been superseded. Open the latest source version before creating a new draft.");
      if(message.contains("COURSE_VERSION_SEQUENCE_INVALID"))
        throw conflict("The new content version must be based on the latest source version.");
      if(message.contains("COURSE_VERSION_FORK_"))
        throw conflict("The course graph could not be copied safely: "+safeReason(message));
      throw ex;
    }
  }

  private static long number(Object value) {
    if(value instanceof Number n) return n.longValue();
    throw new IllegalStateException("The course-version function returned an invalid ID.");
  }

  private static String mostSpecificMessage(Throwable error) {
    Throwable current=error;
    while(current.getCause()!=null && current.getCause()!=current) current=current.getCause();
    return current.getMessage()==null?"":current.getMessage();
  }

  private static String safeReason(String message) {
    int code=message.indexOf("COURSE_VERSION_FORK_");
    if(code<0)return "unmapped course content.";
    String reason=message.substring(code).split("[\\r\\n]",2)[0];
    return reason.length()>180?reason.substring(0,180):reason;
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT,message);
  }
}
