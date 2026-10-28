package com.quantaedge.api;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

/**
 * Renders immutable HRMS settlement statements. A statement is payout evidence only;
 * it is not a tax invoice, TDS/TCS certificate, or proof of tax remittance.
 *
 * The page is raster-rendered so system Noto fonts can shape institution names in
 * Indian scripts; the API runtime image installs fonts-noto-core.
 */
@Service
public class PayoutSettlementPdfRenderer {
  private static final int WIDTH=1240,HEIGHT=1754,RENDER_SCALE=2,MARGIN=64,CONTENT_WIDTH=WIDTH-(MARGIN*2);
  private static final int FOOTER_Y=HEIGHT-68;
  private static final int MAX_PAGES=120;
  private static final Color INK=new Color(0x152038),MUTED=new Color(0x667085),
      LINE=new Color(0xD9DEE7),PAPER=new Color(0xFFFFFF),SUBTLE=new Color(0xF5F7FA),
      BRAND=new Color(0xC2410C),ACCENT=new Color(0x0F766E),WARNING=new Color(0x9A3412),
      WARNING_BG=new Color(0xFFF7ED),SUCCESS=new Color(0x166534),SUCCESS_BG=new Color(0xF0FDF4);
  private static final ZoneId INDIA=ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a 'IST'",Locale.ENGLISH).withZone(INDIA);
  private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd MMM yyyy",Locale.ENGLISH).withZone(INDIA);

  public record RenderedStatement(byte[] pdf,int pageCount) {}

  public RenderedStatement renderBatchReport(Map<String,Object> batch,List<Map<String,Object>> payouts,
      List<Map<String,Object>> allocations,String documentNumber,Instant generatedAt) {
    return render(batch,payouts,allocations,documentNumber,generatedAt,false);
  }

  public RenderedStatement renderRecipientAdvice(Map<String,Object> batch,Map<String,Object> payout,
      List<Map<String,Object>> allocations,String documentNumber,Instant generatedAt) {
    return render(batch,List.of(payout),allocations,documentNumber,generatedAt,true);
  }

  private RenderedStatement render(Map<String,Object> batch,List<Map<String,Object>> payouts,
      List<Map<String,Object>> allocations,String documentNumber,Instant generatedAt,boolean recipientOnly) {
    Map<String,Object> safeBatch=batch==null?Map.of():batch;
    List<Map<String,Object>> safePayouts=payouts==null?List.of():payouts;
    List<Map<String,Object>> safeAllocations=allocations==null?List.of():allocations;
    String batchStatus=string(safeBatch.get("status"));
    String title=recipientOnly?"MONTHLY PAYOUT ADVICE":"MONTHLY PAYOUT BATCH REPORT";
    String created=TIME.format(generatedAt==null?Instant.now():generatedAt);
    ReportCanvas c=new ReportCanvas(title,documentNumber,created);
    c.heading(recipientOnly?"Recipient settlement detail":"Internal finance batch report",
        "Payout cycle: "+date(safeBatch.get("period_start"))+" to "+date(safeBatch.get("period_end")));
    if(!recipientOnly) {
      boolean allProcessed=!safePayouts.isEmpty()
          &&safePayouts.stream().allMatch(p->"PROCESSED".equalsIgnoreCase(string(p.get("status"))));
      c.notice(allProcessed?"TRANSFER STATUS SNAPSHOT":"NOT ALL TRANSFERS CONFIRMED",
          allProcessed
            ?"All listed payouts are marked processed in the saved provider records at generation time; retain the status and UTR for each row."
            :"This batch includes transfers that are pending, failed, reversed or otherwise not confirmed. Do not treat the batch total as money received by recipients.");
    } else {
      Map<String,Object> pay=safePayouts.isEmpty()?Map.of():safePayouts.getFirst();
      boolean processed="PROCESSED".equalsIgnoreCase(string(pay.get("status")));
      c.notice(processed?"PROVIDER STATUS SNAPSHOT":"NOT PROOF OF PAYMENT",
          processed?"RazorpayX reports this transfer as processed as of the statement timestamp.":"This transfer is not confirmed as processed. Check the current provider status before treating it as paid.");
    }
    c.summaryCards(List.of(
        new Metric(recipientOnly?"PAYOUT AMOUNT":"BATCH TRANSFER VALUE",money(recipientOnly?first(safePayouts,"amount_paise"):safeBatch.get("total_amount_paise"),currency(safeBatch))),
        new Metric(recipientOnly?"TRANSFER STATUS":"RECIPIENT TRANSFERS",recipientOnly?label(string(first(safePayouts,"status"))):String.valueOf(safePayouts.size())),
        new Metric(recipientOnly?"BANK REFERENCE":"CONFIRMED PAID",recipientOnly?utr(firstMap(safePayouts)):String.valueOf(countStatus(safePayouts,"PROCESSED"))),
        new Metric(recipientOnly?"BATCH REFERENCE":"EXCEPTIONS",recipientOnly?String.valueOf(safeBatch.getOrDefault("public_id","")):
            String.valueOf(countExceptions(safePayouts)))));
    if(recipientOnly) {
      Map<String,Object> pay=safePayouts.isEmpty()?Map.of():safePayouts.getFirst();
      c.section("RECIPIENT AND TRANSFER");
      c.keyValue("Recipient",firstNonBlank(pay,"recipient_name","beneficiary_name"));
      c.keyValue("Recipient type",label(string(pay.get("beneficiary_type"))));
      c.keyValue("Payout batch",firstNonBlank(safeBatch,"public_id","id"));
      c.keyValue("Batch status",label(batchStatus));
      c.keyValue("Payout status",label(string(pay.get("status"))));
      c.keyValue("Bank account",safeBank(pay));
      c.keyValue("Transfer reference",safeText(pay.get("provider_payout_id")));
      c.keyValue("Bank UTR / transfer reference",utr(pay));
      c.keyValue("Payment collection entity",safeText(safeBatch.get("payment_collection_legal_name")));
      c.keyValue("RazorpayX funding owner",safeText(safeBatch.get("razorpayx_funding_legal_name")));
      c.keyValue("Funding arrangement review",label(string(safeBatch.get("collection_arrangement_status"))));
      c.keyValue("Processed at",dateTime(pay.get("processed_at")));
      c.keyValue("Provider fee (actual)",moneyNullable(pay.get("provider_fee_paise"),currency(pay)));
      c.keyValue("Tax on provider fee (actual)",moneyNullable(pay.get("provider_fee_tax_paise"),currency(pay)));
      c.keyValue("Fee reconciliation",string(pay.get("provider_fee_confirmed_at")).isBlank()?"Not confirmed/reconciled":"Confirmed at "+dateTime(pay.get("provider_fee_confirmed_at")));
    } else {
      c.section("BATCH AND APPROVAL CONTROL");
      c.keyValue("Batch reference",firstNonBlank(safeBatch,"public_id","id"));
      c.keyValue("Batch status",label(batchStatus));
      c.keyValue("Currency",currency(safeBatch));
      c.keyValue("Payment collection entity",safeText(safeBatch.get("payment_collection_legal_name")));
      c.keyValue("RazorpayX funding owner",safeText(safeBatch.get("razorpayx_funding_legal_name")));
      c.keyValue("Funding arrangement review",label(string(safeBatch.get("collection_arrangement_status"))));
      c.keyValue("Prepared by",safeText(safeBatch.get("created_by_name")));
      c.keyValue("Prepared at",dateTime(safeBatch.get("created_at")));
      c.keyValue("Approved by",safeText(safeBatch.get("approved_by_name")));
      c.keyValue("Approved at",dateTime(safeBatch.get("approved_at")));
      c.keyValue("Transfer-fee status",safeBatch.get("provider_fee_reconciled_at")==null
          ?"NOT RECONCILED":"Reconciled at "+dateTime(safeBatch.get("provider_fee_reconciled_at")));
      c.keyValue("Confirmed transfer fees",moneyNullable(safeBatch.get("provider_fee_total_paise"),currency(safeBatch)));
      c.keyValue("Tax on transfer fees",moneyNullable(safeBatch.get("provider_fee_tax_total_paise"),currency(safeBatch)));
      c.keyValue("Platform-borne fee overage",moneyNullable(safeBatch.get("platform_borne_overage_paise"),currency(safeBatch)));
      c.keyValue("Created document",documentNumber);
    }

    c.section(recipientOnly?"ALLOCATION REFERENCES IN THIS PAYOUT":"TRANSFER REGISTER");
    if(recipientOnly) {
      c.muted("Source sales may predate this payout cycle. Allocation values below identify ledger portions applied to this payout, not the month's gross course sales.");
      c.muted("Use the invoice for the underlying taxable supply and the supplier's statutory tax records for tax treatment; this payout advice covers amounts applied to the transfer.");
      c.tableHeader(new String[]{"Course / order / invoice","Legal seller","Source institution","Marketplace institution","Allocation applied"});
      if(safeAllocations.isEmpty())c.empty("No allocation references were returned for this payout. Do not infer a zero balance; check the batch record.");
      for(Map<String,Object> row:safeAllocations) {
        c.tableRow(new String[]{
          text(row.get("course_title"))+"\nOrder #"+text(row.get("order_id"))+" · Allocation #"+text(row.get("allocation_id"))+"\nInvoice: "+safeText(row.get("invoice_number"))+" · Payment: "+safeText(row.get("provider_payment_id")),
          twoLine(safeText(row.get("legal_seller_name")),26),
          twoLine(safeText(row.get("source_institution_name")),26),
          twoLine(safeText(row.get("marketplace_institution_name")),26),
          money(row.get("payout_item_amount_paise"),currency(row))
        },82);
      }
    } else {
      c.tableHeader(new String[]{"Recipient / type","Payout amount","Status","Bank (masked)","Provider payout ID","UTR / reference"});
      if(safePayouts.isEmpty())c.empty("This batch contains no payout records.");
      for(Map<String,Object> row:safePayouts) {
        c.tableRow(new String[]{
          twoLine(firstNonBlank(row,"recipient_name","beneficiary_name")+"\n"+label(string(row.get("beneficiary_type"))),25),
          money(row.get("amount_paise"),currency(row)),
          twoLine(label(string(row.get("status")))+"\nProvider: "+label(string(row.get("provider_status"))),24),
          safeBank(row),
          safeText(row.get("provider_payout_id")),
          utr(row)
        },78);
      }
      c.section("ALLOCATION TRACE");
      c.muted("Allocation-applied values identify ledger portions included in the batch. Source sales may predate this payout cycle; these are not monthly gross sales.");
      c.tableHeader(new String[]{"Recipient","Course / order / invoice","Legal seller","Source -> marketplace","Payment reference","Applied to payout"});
      if(safeAllocations.isEmpty())c.empty("No allocation references were returned for this batch.");
      for(Map<String,Object> row:safeAllocations) {
        c.tableRow(new String[]{
          twoLine(firstNonBlank(row,"recipient_name","beneficiary_name"),25),
          text(row.get("course_title"))+"\nOrder #"+text(row.get("order_id"))+" · Allocation #"+text(row.get("allocation_id"))+"\nInvoice: "+safeText(row.get("invoice_number")),
          twoLine(safeText(row.get("legal_seller_name")),31),
          twoLine(safeText(row.get("source_institution_name"))+" -> "+safeText(row.get("marketplace_institution_name")),31),
          safeText(row.get("provider_payment_id")),
          money(row.get("payout_item_amount_paise"),currency(row))
        },82);
      }
    }

    c.section("TAX AND DOCUMENT SCOPE");
    c.paragraph("This settlement statement does not calculate recipient-level GST TCS or income-tax TDS. No statutory withholding amount is inferred here. Consult the applicable tax invoice/credit note and the responsible legal entity's statutory tax records. This is not a GST tax invoice, a TDS/TCS certificate, a tax remittance receipt, or proof that a pending/failed transfer reached a bank account.");
    c.paragraph("Amounts and statuses represent the saved finance records at the document generation time shown above. Provider status may change after this snapshot; use the live HRMS ledger and provider reconciliation for the current position.");
    return c.toPdf(title,documentNumber,generatedAt);
  }

  private static final class ReportCanvas {
    // Keep compressed page images instead of hundreds of megabytes of live RGB bitmaps.
    private final List<byte[]> pages=new ArrayList<>();
    private final List<TextRun> textRuns=new ArrayList<>();
    private final String title,documentNumber,created;
    private record TextRun(int pageIndex,float x,float baselineY,String text,int style,int size) {}
    private BufferedImage page;
    private Graphics2D g;
    private int y;
    ReportCanvas(String title,String documentNumber,String created){this.title=title;this.documentNumber=documentNumber;this.created=created;newPage(false);}
    void newPage(boolean continued) {
      if(g!=null){footer();g.dispose();g=null;archiveCurrentPage();}
      if(pages.size()>=MAX_PAGES)throw new IllegalArgumentException("The payout statement is too large for one PDF. Use a smaller payout batch.");
      page=new BufferedImage(WIDTH*RENDER_SCALE,HEIGHT*RENDER_SCALE,BufferedImage.TYPE_INT_RGB);
      g=page.createGraphics();
      g.scale(RENDER_SCALE,RENDER_SCALE);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
      g.setColor(PAPER);g.fillRect(0,0,WIDTH,HEIGHT);
      g.setColor(BRAND);g.fillRect(0,0,WIDTH,15);
      g.setColor(INK);g.setFont(font(Font.BOLD,21));drawText("QUANTAEDGE FINANCE",MARGIN,66);
      g.setColor(MUTED);g.setFont(font(Font.PLAIN,14));drawText("HRMS / SETTLEMENT RECORD",MARGIN,91);
      g.setColor(BRAND);g.setFont(font(Font.BOLD,15));
      String documentClass=continued?"CONTINUED":(title.equals("MONTHLY PAYOUT ADVICE")?"RECIPIENT PAYOUT ADVICE":"INTERNAL FINANCE PDF");
      drawText(documentClass,WIDTH-320,66);
      g.setColor(LINE);g.drawLine(MARGIN,112,WIDTH-MARGIN,112);
      g.setColor(INK);g.setFont(font(Font.BOLD,continued?24:28));drawText(continued?title+" (CONTINUED)":title,MARGIN,160);
      g.setColor(MUTED);g.setFont(font(Font.PLAIN,15));
      drawText(clip(documentNumber,360),MARGIN,190);
      drawText("Generated "+clip(created,330),WIDTH-MARGIN-330,190);
      y=230;
    }
    private void drawText(String value,int x,int baselineY) {
      if(value==null||value.isEmpty())return;
      g.drawString(value,x,baselineY);
      textRuns.add(new TextRun(pages.size(),x,baselineY,value,g.getFont().getStyle(),g.getFont().getSize()));
    }
    private void archiveCurrentPage() {
      if(page==null)return;
      try(ByteArrayOutputStream png=new ByteArrayOutputStream()) {
        if(!ImageIO.write(page,"png",png))throw new IOException("PNG encoder is unavailable.");
        pages.add(png.toByteArray());
      } catch(IOException ex) {
        throw new IllegalStateException("A payout PDF page could not be encoded.",ex);
      } finally {
        page.flush();
        page=null;
      }
    }
    void heading(String subTitle,String period) {
      g.setColor(INK);g.setFont(font(Font.BOLD,19));drawText(clip(subTitle,CONTENT_WIDTH),MARGIN,y+8);y+=30;
      g.setColor(MUTED);g.setFont(font(Font.PLAIN,16));drawText(clip(period,CONTENT_WIDTH),MARGIN,y+2);y+=34;
    }
    void notice(String headline,String detail) {
      ensure(88);
      g.setColor(WARNING_BG);g.fillRoundRect(MARGIN,y,CONTENT_WIDTH,75,12,12);
      g.setColor(WARNING);g.setFont(font(Font.BOLD,17));drawText(clip(headline,CONTENT_WIDTH-28),MARGIN+14,y+25);
      g.setColor(INK);g.setFont(font(Font.PLAIN,17));drawText(clip(detail,CONTENT_WIDTH-28),MARGIN+14,y+53);
      y+=88;
    }
    void summaryCards(List<Metric> metrics) {
      int gap=12,width=(CONTENT_WIDTH-gap*(metrics.size()-1))/metrics.size(),h=105;
      ensure(h+14);
      for(int i=0;i<metrics.size();i++) {
        int x=MARGIN+i*(width+gap);
        g.setColor(SUBTLE);g.fillRoundRect(x,y,width,h,12,12);
        g.setColor(MUTED);g.setFont(font(Font.BOLD,15));drawText(clip(metrics.get(i).label(),width-24),x+12,y+27);
        g.setColor(INK);g.setFont(font(Font.BOLD,19));drawText(clip(metrics.get(i).value(),width-24),x+12,y+62);
      }
      y+=h+18;
    }
    void section(String text) {
      ensure(52);
      g.setColor(ACCENT);g.setFont(font(Font.BOLD,18));drawText(clip(text,CONTENT_WIDTH),MARGIN,y+20);
      g.setColor(LINE);g.setStroke(new BasicStroke(1));g.drawLine(MARGIN,y+32,WIDTH-MARGIN,y+32);y+=42;
    }
    void keyValue(String key,String value) {
      ensure(36);
      g.setColor(MUTED);g.setFont(font(Font.PLAIN,18));drawText(clip(key,300),MARGIN,y+22);
      g.setColor(INK);g.setFont(font(Font.BOLD,18));drawText(clip(value,CONTENT_WIDTH-320),MARGIN+320,y+22);
      g.setColor(LINE);g.drawLine(MARGIN,y+30,WIDTH-MARGIN,y+30);y+=34;
    }
    void muted(String text) {
      ensure(36);g.setColor(MUTED);g.setFont(font(Font.PLAIN,16));drawText(clip(text,CONTENT_WIDTH),MARGIN,y+17);y+=31;
    }
    void paragraph(String text) {
      String[] lines=wrap(text,CONTENT_WIDTH,font(Font.PLAIN,18));
      ensure(Math.max(36,lines.length*25+8));
      g.setColor(INK);g.setFont(font(Font.PLAIN,18));
      for(String line:lines){if(y+27>FOOTER_Y-24){newPage(true);g.setColor(INK);g.setFont(font(Font.PLAIN,18));}drawText(line,MARGIN,y+20);y+=25;}
      y+=8;
    }
    void empty(String text) { paragraph(text); }
    void tableHeader(String[] labels) {
      ensure(49);
      int[] widths=columns(labels.length);
      g.setColor(INK);g.fillRoundRect(MARGIN,y,CONTENT_WIDTH,42,8,8);
      g.setColor(Color.WHITE);g.setFont(font(Font.BOLD,16));
      int x=MARGIN;
      for(int i=0;i<labels.length;i++){drawText(clip(labels[i],widths[i]-12),x+8,y+26);x+=widths[i];}
      y+=46;
    }
    void tableRow(String[] values,int rowHeight) {
      ensure(rowHeight+4);
      int[] widths=columns(values.length);
      g.setColor(((pages.size()+1)%2)==0?SUBTLE:Color.WHITE);g.fillRect(MARGIN,y,CONTENT_WIDTH,rowHeight);
      g.setColor(LINE);g.setStroke(new BasicStroke(1));g.drawLine(MARGIN,y+rowHeight,WIDTH-MARGIN,y+rowHeight);
      g.setColor(INK);g.setFont(font(Font.PLAIN,18));
      int x=MARGIN;
      for(int i=0;i<values.length;i++) {
        String[] parts=String.valueOf(values[i]==null?"":values[i]).split("\n",-1);
        int lineY=y+22;
        for(int j=0;j<Math.min(parts.length,3);j++){drawText(clip(parts[j],widths[i]-14),x+7,lineY);lineY+=21;}
        x+=widths[i];
      }
      y+=rowHeight;
    }
    void ensure(int needed) {if(y+needed>FOOTER_Y-30)newPage(true);}
    void footer() {
      g.setColor(LINE);g.drawLine(MARGIN,FOOTER_Y-20,WIDTH-MARGIN,FOOTER_Y-20);
      g.setColor(MUTED);g.setFont(font(Font.PLAIN,12));
      drawText("Internal finance record. Not a tax invoice, statutory TDS/TCS certificate, or tax-remittance receipt.",MARGIN,FOOTER_Y);
      drawText("Page "+(pages.size()+1),WIDTH-MARGIN-58,FOOTER_Y);
    }
    RenderedStatement toPdf(String title,String number,Instant time) {
      if(g!=null){footer();g.dispose();g=null;archiveCurrentPage();}
      try(PDDocument document=new PDDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
        PDDocumentInformation info=new PDDocumentInformation();
        info.setTitle(title+" - "+number);
        info.setSubject("Versioned QuantaEdge HRMS payout statement; not a fiscal document");
        info.setCreator("QuantaEdge HRMS Finance");
        Calendar calendar=Calendar.getInstance(java.util.TimeZone.getTimeZone(INDIA));
        calendar.setTimeInMillis((time==null?Instant.now():time).toEpochMilli());
        info.setCreationDate(calendar);document.setDocumentInformation(info);
        PDType0Font searchableFont=loadSearchableFont(document);
        float scale=PDRectangle.A4.getWidth()/WIDTH;
        int pageIndex=0;
        for(byte[] pagePng:pages) {
          BufferedImage image=ImageIO.read(new ByteArrayInputStream(pagePng));
          if(image==null)throw new IOException("A stored payout PDF page is not a valid PNG.");
          try {
            PDPage pdfPage=new PDPage(PDRectangle.A4);document.addPage(pdfPage);
            PDImageXObject embedded=LosslessFactory.createFromImage(document,image);
            try(PDPageContentStream stream=new PDPageContentStream(document,pdfPage)) {
              stream.drawImage(embedded,0,0,pdfPage.getMediaBox().getWidth(),pdfPage.getMediaBox().getHeight());
            }
            // Preserve the pixel-perfect visual page but layer invisible selectable Unicode text on top.
            // This makes statements searchable/copyable and improves assistive-tool text extraction.
            try(PDPageContentStream textStream=new PDPageContentStream(document,pdfPage,
                PDPageContentStream.AppendMode.APPEND,true,true)) {
              // Use transparent fill text rather than Tr=3 (NEITHER). Some PDF text
              // extractors ignore glyphs in NEITHER mode; alpha-zero fill preserves the
              // raster-only appearance while keeping a real Unicode text layer searchable.
              PDExtendedGraphicsState invisibleText=new PDExtendedGraphicsState();
              invisibleText.setNonStrokingAlphaConstant(0f);
              textStream.setGraphicsStateParameters(invisibleText);
              textStream.setRenderingMode(RenderingMode.FILL);
              for(TextRun run:textRuns) {
                if(run.pageIndex()!=pageIndex)continue;
                String text=pdfSafeText(run.text(),searchableFont);
                if(text.isEmpty())continue;
                textStream.beginText();
                textStream.setFont(searchableFont,Math.max(3f,run.size()*scale));
                textStream.newLineAtOffset(run.x()*scale,pdfPage.getMediaBox().getHeight()-run.baselineY()*scale);
                textStream.showText(text);
                textStream.endText();
              }
            }
          } finally {
            image.flush();
          }
          pageIndex++;
        }
        document.save(output);
        return new RenderedStatement(output.toByteArray(),pages.size());
      } catch(IOException ex) {
        throw new IllegalStateException("Payout statement PDF could not be rendered.",ex);
      }
    }
    private static int[] columns(int count) {
      return switch(count) {
        case 4 -> new int[]{360,245,245,CONTENT_WIDTH-850};
        case 5 -> new int[]{200,250,255,225,CONTENT_WIDTH-930};
        case 6 -> new int[]{255,145,190,180,160,CONTENT_WIDTH-930};
        default -> {
          int[] widths=new int[count];int each=CONTENT_WIDTH/count;java.util.Arrays.fill(widths,each);
          widths[count-1]=CONTENT_WIDTH-each*(count-1);yield widths;
        }
      };
    }
  }

  private static PDType0Font loadSearchableFont(PDDocument document) throws IOException {
    List<Path> candidates=List.of(
        Path.of("/usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf"),
        Path.of("/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"),
        Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"));
    for(Path candidate:candidates) {
      if(Files.isRegularFile(candidate)) {
        try(InputStream input=Files.newInputStream(candidate)) {
          return PDType0Font.load(document,input);
        }
      }
    }
    throw new IOException("A Unicode font is required to create searchable settlement statements.");
  }
  private static String pdfSafeText(String value,PDType0Font font) {
    StringBuilder safe=new StringBuilder();
    for(int offset=0;offset<value.length();) {
      int cp=value.codePointAt(offset);
      String glyph=new String(Character.toChars(cp));
      try {
        font.encode(glyph);
        safe.append(glyph);
      } catch(IOException|IllegalArgumentException unsupportedGlyph) {
        // The visual layer remains intact; omit only an unencodable glyph from the searchable layer.
      }
      offset+=Character.charCount(cp);
    }
    return safe.toString();
  }

  private record Metric(String label,String value) {}
  private static Font font(int style,int size){return new Font(Font.SANS_SERIF,style,size);}
  private static String money(Object paise,String currency) {
    if(paise==null)return "Not available";
    try {
      BigDecimal amount=new BigDecimal(String.valueOf(paise)).divide(BigDecimal.valueOf(100),2,RoundingMode.HALF_UP);
      return currency+" "+String.format(Locale.US,"%,.2f",amount);
    } catch(Exception ignored){return "Not available";}
  }
  private static String moneyNullable(Object value,String currency){return value==null?"Not reconciled":money(value,currency);}
  private static String currency(Map<String,Object> row){return string(row.get("currency")).isBlank()?"INR":string(row.get("currency"));}
  private static Object first(List<Map<String,Object>> rows,String key){return rows==null||rows.isEmpty()?null:rows.getFirst().get(key);}
  private static Map<String,Object> firstMap(List<Map<String,Object>> rows){return rows==null||rows.isEmpty()?Map.of():rows.getFirst();}
  private static String utr(Map<String,Object> row){
    String ref=string(row.get("transfer_reference"));
    if(ref.isBlank())ref=string(row.get("utr"));
    return ref.isBlank()?"Not available":ref;
  }
  private static String safeBank(Map<String,Object> row){
    String ifsc=string(row.get("bank_ifsc")),last4=string(row.get("bank_account_last4"));
    if(ifsc.isBlank()&&last4.isBlank())return "Bank details not available";
    return (ifsc.isBlank()?"IFSC not available":ifsc)+" / ****"+(last4.isBlank()?"????":last4);
  }
  private static String date(Object value){
    if(value==null)return "Not available";
    String raw=String.valueOf(value);
    try{return raw.length()>=10?raw.substring(0,10):raw;}catch(Exception ignored){return raw;}
  }
  private static String dateTime(Object value){
    if(value==null||String.valueOf(value).isBlank())return "Not available";
    try{return TIME.format(Instant.parse(String.valueOf(value)));}catch(Exception ignored){}
    try{return TIME.format(java.time.OffsetDateTime.parse(String.valueOf(value)).toInstant());}catch(Exception ignored){}
    return String.valueOf(value);
  }
  private static String label(String value){return value==null||value.isBlank()?"Not available":value.replace('_',' ');}
  private static String safeText(Object value){String text=value==null?"":String.valueOf(value).replaceAll("[\\p{Cntrl}]"," ").trim();return text.isBlank()?"Not available":text;}
  private static String text(Object value){return safeText(value);}
  private static String firstNonBlank(Map<String,Object> row,String a,String b){
    String first=string(row.get(a));return first.isBlank()?safeText(row.get(b)):first;
  }
  private static String twoLine(String value,int max){
    String v=value==null?"":value;
    if(v.length()<=max)return v;
    int split=v.lastIndexOf(' ',Math.min(max,v.length()-1));
    if(split<Math.max(8,max/2))split=Math.min(max,v.length());
    return v.substring(0,split).trim()+"\n"+v.substring(split).trim();
  }
  private static String[] wrap(String text,int width,Font font) {
    BufferedImage scratch=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);
    Graphics2D g=scratch.createGraphics();
    try {
      g.setFont(font);java.util.List<String> lines=new ArrayList<>();StringBuilder line=new StringBuilder();
      for(String word:text.split("\\s+")) {
        String candidate=line.isEmpty()?word:line+" "+word;
        if(g.getFontMetrics().stringWidth(candidate)>width&&!line.isEmpty()){lines.add(line.toString());line.setLength(0);line.append(word);}
        else{if(!line.isEmpty())line.append(' ');line.append(word);}
      }
      if(!line.isEmpty())lines.add(line.toString());
      return lines.toArray(String[]::new);
    } finally {g.dispose();}
  }
  private static final java.awt.font.FontRenderContext FONT_CONTEXT=
      new java.awt.font.FontRenderContext(new java.awt.geom.AffineTransform(),true,true);
  private static String clip(String value,int width) {
    if(value==null)return "";
    String clean=value.replaceAll("[\\p{Cntrl}]"," ").trim();
    if(clean.length()<2)return clean;
    // Use the logical font's metrics without allocating a new image for every table cell.
    Font measuredFont=font(Font.PLAIN,18);
    if(measuredFont.getStringBounds(clean,FONT_CONTEXT).getWidth()<=width)return clean;
    StringBuilder out=new StringBuilder();
    for(int i=0;i<clean.length();) {
      int cp=clean.codePointAt(i);String next=out.toString()+new String(Character.toChars(cp))+"...";
      if(measuredFont.getStringBounds(next,FONT_CONTEXT).getWidth()>width)break;
      out.appendCodePoint(cp);i+=Character.charCount(cp);
    }
    return out+"...";
  }
  private static long countStatus(List<Map<String,Object>> payouts,String status){
    return payouts.stream().filter(p->status.equalsIgnoreCase(string(p.get("status")))).count();
  }
  private static long countExceptions(List<Map<String,Object>> payouts){
    return payouts.stream().filter(p->List.of("FAILED","REJECTED","REVERSED","RECONCILIATION_REQUIRED").contains(string(p.get("status")))).count();
  }
  private static String string(Object value){return value==null?"":String.valueOf(value).trim();}
}
