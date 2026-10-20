package com.quantaedge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Purchaser-scoped invoice listing and private PDF delivery for student and parent workspaces. */
@RestController
@RequestMapping("/api/v1/marketplace")
public class CourseInvoiceController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final CourseOrderFinancialService financialService;
  private final ObjectMapper mapper;

  public CourseInvoiceController(
      JdbcTemplate jdbc,AuthorizationService authorization,
      CourseOrderFinancialService financialService,ObjectMapper mapper) {
    this.jdbc=jdbc;
    this.authorization=authorization;
    this.financialService=financialService;
    this.mapper=mapper;
  }

  @GetMapping("/invoices")
  public List<Map<String,Object>> studentInvoices(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context=authorization.requireStudent(context);
    long institutionId=requireTenant(tenant);
    return jdbc.queryForList("""
      select o.id as course_order_id,d.id as invoice_document_id,d.document_number,d.document_type,
        d.issued_at,d.currency,d.taxable_value_paise,d.discount_value_paise,d.tax_value_paise,
        d.total_value_paise,d.amount_paid_paise,
        coalesce(nullif(offer.title_override,''),p.display_name) as course_title,
        true as pdf_available
      from course_payment_order o
      join fiscal_document d on d.id=o.fiscal_document_id
      join fiscal_document_asset a on a.fiscal_document_id=d.id and a.asset_type='PDF'
      join curriculum_program p on p.id=o.course_id
      join institution_marketplace_offer offer on offer.id=o.institution_offer_id
      where o.student_id=? and o.institution_id=? and o.status='VERIFIED'
        and o.purchaser_user_id is null
      order by d.issued_at desc,d.id desc
      limit 100
      """,context.studentId(),institutionId);
  }

  @GetMapping("/family/invoices")
  public List<Map<String,Object>> parentInvoices(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context=authorization.requireParent(context);
    long institutionId=requireTenant(tenant);
    return jdbc.queryForList("""
      select o.id as course_order_id,d.id as invoice_document_id,d.document_number,d.document_type,
        d.issued_at,d.currency,d.taxable_value_paise,d.discount_value_paise,d.tax_value_paise,
        d.total_value_paise,d.amount_paid_paise,
        coalesce(nullif(offer.title_override,''),p.display_name) as course_title,
        learner.display_name as beneficiary_name,
        true as pdf_available
      from course_payment_order o
      join fiscal_document d on d.id=o.fiscal_document_id
      join fiscal_document_asset a on a.fiscal_document_id=d.id and a.asset_type='PDF'
      join curriculum_program p on p.id=o.course_id
      join institution_marketplace_offer offer on offer.id=o.institution_offer_id
      join student learner on learner.id=o.student_id
      where o.purchaser_user_id=? and o.institution_id=? and o.status='VERIFIED'
      order by d.issued_at desc,d.id desc
      limit 100
      """,context.userId(),institutionId);
  }

  @GetMapping(value="/orders/{orderId}/invoice.pdf",produces=MediaType.APPLICATION_PDF_VALUE)
  @Transactional
  public ResponseEntity<byte[]> downloadStudentInvoice(
      @PathVariable long orderId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context=authorization.requireStudent(context);
    long institutionId=requireTenant(tenant);
    requireStudentOrder(orderId,context.studentId(),institutionId);
    return buildDownload(orderId,"STUDENT",context.userId());
  }

  @GetMapping(value="/family/orders/{orderId}/invoice.pdf",produces=MediaType.APPLICATION_PDF_VALUE)
  @Transactional
  public ResponseEntity<byte[]> downloadParentInvoice(
      @PathVariable long orderId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context=authorization.requireParent(context);
    long institutionId=requireTenant(tenant);
    requireParentOrder(orderId,context.userId(),institutionId);
    return buildDownload(orderId,"PARENT",context.userId());
  }

  private void requireStudentOrder(long orderId,long studentId,long institutionId) {
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from course_payment_order
        where id=? and student_id=? and institution_id=?
          and purchaser_user_id is null and status='VERIFIED'
          and fiscal_document_id is not null)
      """,Boolean.class,orderId,studentId,institutionId);
    if(!Boolean.TRUE.equals(allowed)) throw new ResponseStatusException(
        HttpStatus.NOT_FOUND,"The invoice is not available to this student account.");
  }

  private void requireParentOrder(long orderId,long purchaserUserId,long institutionId) {
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from course_payment_order
        where id=? and purchaser_user_id=? and institution_id=?
          and status='VERIFIED' and fiscal_document_id is not null)
      """,Boolean.class,orderId,purchaserUserId,institutionId);
    if(!Boolean.TRUE.equals(allowed)) throw new ResponseStatusException(
        HttpStatus.NOT_FOUND,"The invoice is not available to this parent account.");
  }

  private ResponseEntity<byte[]> buildDownload(long orderId,String actorType,long actorUserId) {
    CourseOrderFinancialService.DownloadableInvoice invoice=financialService.invoiceDownload(orderId);
    byte[] bytes=financialService.readInvoice(invoice);
    try {
      jdbc.update("""
        insert into fiscal_document_event(fiscal_document_id,event_type,idempotency_key,details)
        values(?,'DOWNLOAD_AUDITED',?,?::jsonb)
        """,invoice.documentId(),"invoice-download:"+UUID.randomUUID(),
          mapper.writeValueAsString(Map.of("actorType",actorType,"actorUserId",actorUserId,"courseOrderId",orderId)));
    } catch(Exception ex) {
      throw new IllegalStateException("The invoice download could not be recorded in the audit trail.",ex);
    }
    String safeName=invoice.documentNumber().replaceAll("[^A-Za-z0-9_-]","-");
    return ResponseEntity.ok()
      .contentType(MediaType.APPLICATION_PDF)
      .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+safeName+".pdf\"")
      .header(HttpHeaders.CACHE_CONTROL,"private, no-store, max-age=0")
      .header("X-Content-Type-Options","nosniff")
      .body(bytes);
  }

  private static long requireTenant(InstitutionTenantContext tenant) {
    if(tenant==null) throw new InstitutionTenantContextService.InvalidInstitutionContextException(
        400,"INSTITUTION_CONTEXT_REQUIRED","Select an institution before opening invoices.");
    return tenant.institutionId();
  }
}
