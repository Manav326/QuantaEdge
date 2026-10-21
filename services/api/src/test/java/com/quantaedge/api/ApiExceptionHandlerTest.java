package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTest {
  @Test
  void missingInstitutionContextIsReturnedAsClientErrorInsteadOfInternalServerError() {
    var exception = new InstitutionTenantContextService.InvalidInstitutionContextException(
        400, "INSTITUTION_CONTEXT_REQUIRED", "Select the institution before signing in.");

    var response = new ApiExceptionHandler().institutionContext(exception);

    assertEquals(400, response.getStatusCode().value());
    assertEquals("INSTITUTION_CONTEXT_REQUIRED", response.getBody().get("error"));
    assertEquals("Select the institution before signing in.", response.getBody().get("message"));
  }

  @Test
  void responseStatusConflictsReturnTheBusinessReason() {
    var exception = new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.CONFLICT,
        "This existing certificate has no matching course association.");

    var response = new ApiExceptionHandler().responseStatus(exception);

    assertEquals(409, response.getStatusCode().value());
    assertEquals("HTTP_409", response.getBody().get("error"));
    assertEquals("This existing certificate has no matching course association.", response.getBody().get("message"));
  }

  @Test
  void institutionAccessFailuresPreserveTheirForbiddenStatus() {
    var exception = new InstitutionTenantContextService.InvalidInstitutionContextException(
        403, "INSTITUTION_UNAVAILABLE", "The selected institution is unavailable.");

    var response = new ApiExceptionHandler().institutionContext(exception);

    assertEquals(403, response.getStatusCode().value());
    assertEquals("INSTITUTION_UNAVAILABLE", response.getBody().get("error"));
    assertEquals("The selected institution is unavailable.", response.getBody().get("message"));
  }
}
