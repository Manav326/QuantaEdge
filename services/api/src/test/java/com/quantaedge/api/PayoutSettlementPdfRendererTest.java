package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PayoutSettlementPdfRendererTest {
  private final PayoutSettlementPdfRenderer renderer=new PayoutSettlementPdfRenderer();

  @Test
  void createsArchivedStyleBatchReportWithContinuationPagesAndA4Geometry() throws Exception {
    Map<String,Object> batch=batch();
    List<Map<String,Object>> payouts=new ArrayList<>();
    List<Map<String,Object>> allocations=new ArrayList<>();
    for(int i=1;i<=36;i++) {
      payouts.add(Map.of(
          "id",(long)i,"recipient_name","Institution "+i,"beneficiary_name","Institution "+i,
          "beneficiary_type","INSTITUTION","amount_paise",10000L+i,"currency","INR",
          "status",i%4==0?"FAILED":"PROCESSED","bank_ifsc","HDFC0000123",
          "bank_account_last4","0123","provider_payout_id","pout_"+i,
          "transfer_reference",i%4==0?"":"UTR-"+i,
          "provider_fee_paise",590L,"provider_fee_tax_paise",90L));
      allocations.add(Map.of(
          "payout_id",(long)i,"payout_item_amount_paise",10000L,
          "allocation_id",(long)(1000+i),"order_id",(long)(2000+i),
          "provider_payment_id","pay_"+i,"course_title","Mathematics "+i,
          "source_institution_name","Source School "+i,
          "marketplace_institution_name","Marketplace School "+i,
          "currency","INR","recipient_name","Institution "+i));
    }
    var rendered=renderer.renderBatchReport(batch,payouts,allocations,"QE-PS-4-B-R1",Instant.parse("2026-10-27T12:00:00Z"));
    assertTrue(rendered.pdf().length>10_000);
    assertTrue(rendered.pageCount()>=3,"Long monthly payout reports must continue to additional pages.");
    try(var document=Loader.loadPDF(rendered.pdf())) {
      assertEquals(rendered.pageCount(),document.getNumberOfPages());
      assertTrue(document.getDocumentInformation().getTitle().contains("MONTHLY PAYOUT BATCH REPORT"));
      String searchableText=new PDFTextStripper().getText(document);
      assertTrue(searchableText.contains("MONTHLY PAYOUT BATCH REPORT"),"PDF text must be selectable/searchable, not only a page image.");
      assertTrue(searchableText.contains("Institution 1"),"Recipient names should be available to search and assistive tools.");
      for(var page:document.getPages()) {
        PDRectangle media=page.getMediaBox();
        assertTrue(media.getHeight()>media.getWidth(),"Settlement PDF pages must use portrait A4.");
        assertTrue(page.getResources().getXObjectNames().iterator().hasNext(),"Each page should contain its rendered page content.");
      }
    }
  }

  @Test
  void recipientAdviceIncludesOneRecipientOnlyAndBuildsWithHindiNames() throws Exception {
    Map<String,Object> batch=batch();
    Map<String,Object> payout=new LinkedHashMap<>();
    payout.put("id",91L);payout.put("recipient_name","विद्यालय सूर्योदय");
    payout.put("beneficiary_name","विद्यालय सूर्योदय");payout.put("beneficiary_type","INSTITUTION");
    payout.put("amount_paise",123456L);payout.put("currency","INR");payout.put("status","PENDING");
    payout.put("bank_ifsc","SBIN0000123");payout.put("bank_account_last4","9876");
    payout.put("provider_payout_id","pout_pending_91");
    List<Map<String,Object>> allocations=List.of(Map.of(
        "payout_id",91L,"payout_item_amount_paise",123456L,
        "allocation_id",220L,"order_id",330L,"provider_payment_id","pay_330",
        "course_title","गणित कक्षा ६","source_institution_name","मूल विद्यालय",
        "marketplace_institution_name","साझा विद्यालय","currency","INR",
        "recipient_name","विद्यालय सूर्योदय"));
    var rendered=renderer.renderRecipientAdvice(batch,payout,allocations,"QE-PS-4-P91-R1",Instant.parse("2026-10-27T12:00:00Z"));
    assertTrue(rendered.pdf().length>5_000);
    assertEquals(1,rendered.pageCount());
    try(var document=Loader.loadPDF(rendered.pdf())) {
      assertEquals(1,document.getNumberOfPages());
      assertTrue(document.getPage(0).getResources().getXObjectNames().iterator().hasNext());
      String searchableText=new PDFTextStripper().getText(document);
      assertTrue(searchableText.contains("गणित"),"Hindi course names should remain searchable in the PDF text layer.");
      assertTrue(searchableText.contains("विद्यालय सूर्योदय"),"Hindi recipient names should remain searchable in the PDF text layer.");
    }
  }

  private static Map<String,Object> batch() {
    Map<String,Object> batch=new LinkedHashMap<>();
    batch.put("id",4L);batch.put("public_id","batch-public-4");
    batch.put("period_start","2026-10-01");batch.put("period_end","2026-10-31");
    batch.put("currency","INR");batch.put("status","PROCESSED");
    batch.put("beneficiary_count",36);batch.put("total_amount_paise",360000L);
    batch.put("created_by_name","Finance Preparer");batch.put("created_at","2026-10-27T12:00:00Z");
    batch.put("approved_by_name","Finance Checker");batch.put("approved_at","2026-10-27T12:05:00Z");
    batch.put("provider_fee_reconciled_at","2026-10-27T12:10:00Z");
    batch.put("provider_fee_total_paise",21240L);batch.put("provider_fee_tax_total_paise",3240L);
    batch.put("platform_borne_overage_paise",0L);
    return batch;
  }
}
