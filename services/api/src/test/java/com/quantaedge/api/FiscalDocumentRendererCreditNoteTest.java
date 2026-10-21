package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;

class FiscalDocumentRendererCreditNoteTest {
  @Test
  void rendersCreditNoteAsAValidSinglePagePdfWithOriginalInvoiceReference() throws Exception {
    FiscalDocumentRenderer renderer=new FiscalDocumentRenderer();
    Map<String,Object> supplier=Map.of(
        "legalName","QuantaEdge Test Seller",
        "registeredAddress",Map.of("line1","1 Demo Road","city","Patna","state","Bihar","postalCode","800001"),
        "gstin","100000000000000",
        "invoiceSeriesCode","QE");
    Map<String,Object> recipient=Map.of(
        "legalName","Test Purchaser",
        "billingAddress",Map.of("line1","2 Demo Road","city","Patna","state","Bihar","postalCode","800001"),
        "beneficiaryName","Test Learner");
    Map<String,Object> transaction=Map.ofEntries(
        Map.entry("currency","INR"),
        Map.entry("courseTitle","Class 6 Mathematics"),
        Map.entry("orderReference","QE-ORDER-12"),
        Map.entry("institutionName","QuantaEdge"),
        Map.entry("paymentReference","rfnd_TEST123"),
        Map.entry("originalDocumentNumber","QE/26-27/000001"),
        Map.entry("listedPricePaise",11800L),
        Map.entry("discountValuePaise",0L),
        Map.entry("taxableValuePaise",10000L),
        Map.entry("taxValuePaise",1800L),
        Map.entry("amountPaidPaise",11800L),
        Map.entry("paymentStatus","REFUND PROCESSED"));
    Map<String,Object> taxSummary=Map.of(
        "serviceClassificationCode","9983",
        "taxTreatment","TAXABLE",
        "components",List.of(
            Map.of("name","CGST","rateBasisPoints",900,"amountPaise",900L),
            Map.of("name","SGST","rateBasisPoints",900,"amountPaise",900L)));
    Map<String,Object> line=Map.of(
        "description","Refund — Class 6 Mathematics",
        "serviceClassificationCode","9983",
        "quantity",1,
        "unitPricePaise",11800L,
        "discountValuePaise",0L,
        "taxableValuePaise",10000L,
        "taxValuePaise",1800L,
        "lineTotalPaise",11800L);

    FiscalDocumentRenderer.RenderedFiscalDocument rendered=renderer.render(
        "CREDIT_NOTE","QE/26-27/000001",java.time.LocalDate.of(2026,10,20),
        supplier,recipient,transaction,taxSummary,List.of(line),
        Map.of("primaryColor","#F97316","accentColor","#0F766E","surfaceStyle","WARM_PAPER",
            "layout","EDITORIAL","showLogo",true,"footerText","Retain this credit note with the original invoice."));
    assertTrue(rendered.pdf().length>1_000);
    assertTrue(rendered.png().length>1_000);
    try(var document=Loader.loadPDF(rendered.pdf())) {
      assertEquals(1,document.getNumberOfPages());
      assertTrue(document.getPage(0).getMediaBox().getHeight()>document.getPage(0).getMediaBox().getWidth());
    }
  }
}
