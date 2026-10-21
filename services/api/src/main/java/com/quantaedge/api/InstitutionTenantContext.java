package com.quantaedge.api;

/**
 * A server-resolved institution identity for one request.
 *
 * This record carries request context only. It does not prove membership, course assignment,
 * marketplace publication, purchase, enrolment or permission to read institution data.
 */
public record InstitutionTenantContext(
    long institutionId,
    String tenantCode,
    String displayName) {
}
