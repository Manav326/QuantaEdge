package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class FinancePayoutStatementDownloadTest {
  private static final AuthContext FINANCE_STAFF =
      new AuthContext(null,null,42L,"ADMIN","Finance test operator","HRMS");
  private static final String STORAGE_KEY = "123e4567-e89b-12d3-a456-426614174000.pdf";

  @Test
  void deniesDownloadBeforeReadingTheArchiveWhenFinancePermissionIsMissing() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    AuthorizationService authorization=mock(AuthorizationService.class);
    FiscalDocumentStorage storage=mock(FiscalDocumentStorage.class);
    when(authorization.requirePermission(FINANCE_STAFF,"QE_FINANCE_PAYOUT_MANAGE"))
        .thenThrow(new SecurityException("permission denied"));
    FinancePayoutController controller=controller(jdbc,authorization,storage);

    assertThrows(SecurityException.class,()->controller.downloadPayoutStatement(7L,FINANCE_STAFF));

    verify(jdbc,never()).queryForList(anyString(),any(Object[].class));
    verify(storage,never()).readPdf(anyString());
  }

  @Test
  void rejectsCorruptedArchivedBytesWithoutReturningPdfOrWritingDownloadAudit() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    AuthorizationService authorization=authorized();
    FiscalDocumentStorage storage=mock(FiscalDocumentStorage.class);
    byte[] pdf=samplePdf();
    when(jdbc.queryForList(anyString(),any(Object[].class)))
        .thenReturn(List.of(archiveRow(pdf,"0".repeat(64))));
    when(storage.readPdf(STORAGE_KEY)).thenReturn(pdf);
    FinancePayoutController controller=controller(jdbc,authorization,storage);

    ResponseStatusException error=assertThrows(ResponseStatusException.class,
        ()->controller.downloadPayoutStatement(7L,FINANCE_STAFF));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,error.getStatusCode());
    verify(jdbc,never()).update(anyString(),any(Object[].class));
  }

  @Test
  void returnsOnlyChecksumVerifiedPdfAndAuditsTheDownload() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    AuthorizationService authorization=authorized();
    FiscalDocumentStorage storage=mock(FiscalDocumentStorage.class);
    byte[] pdf=samplePdf();
    String checksum=FiscalDocumentStorage.sha256(pdf);
    when(jdbc.queryForList(anyString(),any(Object[].class)))
        .thenReturn(List.of(archiveRow(pdf,checksum)));
    when(storage.readPdf(STORAGE_KEY)).thenReturn(pdf);
    FinancePayoutController controller=controller(jdbc,authorization,storage);

    ResponseEntity<byte[]> response=controller.downloadPayoutStatement(7L,FINANCE_STAFF);

    assertEquals(HttpStatus.OK,response.getStatusCode());
    assertEquals(MediaType.APPLICATION_PDF,response.getHeaders().getContentType());
    assertEquals("private, no-store, max-age=0",response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));
    assertEquals(checksum,response.getHeaders().getFirst("X-QE-Statement-SHA256"));
    assertArrayEquals(pdf,response.getBody());
    verify(jdbc).update(contains("finance_payout_statement_event"),any(Object[].class));
  }

  @Test
  void batchSummaryDownloadDoesNotInventAPayoutIdAndAuditsTheRevision() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    AuthorizationService authorization=authorized();
    FiscalDocumentStorage storage=mock(FiscalDocumentStorage.class);
    byte[] pdf=samplePdf();
    String checksum=FiscalDocumentStorage.sha256(pdf);
    Map<String,Object> row=archiveRow(pdf,checksum);
    row.put("payout_id",null);
    row.put("document_type","BATCH_SUMMARY");
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row));
    when(storage.readPdf(STORAGE_KEY)).thenReturn(pdf);
    FinancePayoutController controller=controller(jdbc,authorization,storage);

    ResponseEntity<byte[]> response=controller.downloadPayoutStatement(7L,FINANCE_STAFF);

    assertEquals(HttpStatus.OK,response.getStatusCode());
    ArgumentCaptor<Object[]> auditArgs=ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(contains("finance_payout_statement_event"),auditArgs.capture());
    String details=String.valueOf(auditArgs.getValue()[3]);
    assertTrue(details.contains("\\"payoutId\\":null"),"Batch statements must not create a fictional payout #0.");
    assertTrue(details.contains("\\"revision\\":1"),"Downloads must retain the immutable document revision in audit details.");
  }

  private static AuthorizationService authorized() {
    AuthorizationService authorization=mock(AuthorizationService.class);
    when(authorization.requirePermission(FINANCE_STAFF,"QE_FINANCE_PAYOUT_MANAGE"))
        .thenReturn(FINANCE_STAFF);
    return authorization;
  }

  private static FinancePayoutController controller(JdbcTemplate jdbc,
      AuthorizationService authorization,FiscalDocumentStorage storage) {
    return new FinancePayoutController(jdbc,authorization,
        mock(CourseOrderFinancialService.class),new ObjectMapper(),
        mock(PayoutSettlementPdfRenderer.class),storage,
        "","","","","https://api.razorpay.com/v1");
  }

  private static Map<String,Object> archiveRow(byte[] pdf,String checksum) {
    Map<String,Object> row=new LinkedHashMap<>();
    row.put("id",7L);
    row.put("batch_id",3L);
    row.put("payout_id",11L);
    row.put("document_number","QE-PS-3-P11-R1");
    row.put("storage_key",STORAGE_KEY);
    row.put("sha256",checksum);
    row.put("byte_length",(long)pdf.length);
    row.put("document_type","RECIPIENT_STATEMENT");
    row.put("revision",1);
    return row;
  }

  private static byte[] samplePdf() {
    return ("%PDF-1.7\n% QuantaEdge payout statement test fixture\n").getBytes(StandardCharsets.US_ASCII);
  }
}
