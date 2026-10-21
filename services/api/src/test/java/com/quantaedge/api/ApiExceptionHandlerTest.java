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
  void institutionAccessFailuresPreserveTheirForbiddenStatus() {
    var exception = new InstitutionTenantContextService.InvalidInstitutionContextException(
        403, "INSTITUTION_UNAVAILABLE", "The selected institution is unavailable.");

    var response = new ApiExceptionHandler().institutionContext(exception);

    assertEquals(403, response.getStatusCode().value());
    assertEquals("INSTITUTION_UNAVAILABLE", response.getBody().get("error"));
    assertEquals("The selected institution is unavailable.", response.getBody().get("message"));
  }
}
