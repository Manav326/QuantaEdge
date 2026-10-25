package com.quantaedge.api;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

/** Renders issued course-sale fiscal documents from immutable transaction snapshots. */
@Service
public class FiscalDocumentRenderer {
  private static final int WIDTH=1240, HEIGHT=1754;
  private static final Color NAVY=new Color(0x152038);
  private static final Color INK=new Color(0x263247);
  private static final Color MUTED=new Color(0x68758A);
  private static final Color LINE=new Color(0xE2E6EC);
  private static final Color ORANGE=new Color(0xF97316);
  private static final Color TEAL=new Color(0x0F766E);

  public record RenderedFiscalDocument(byte[] pdf,byte[] png) {}

  public RenderedFiscalDocument render(
      String documentType,
      String documentNumber,
      LocalDate issueDate,
      Map<String,Object> supplier,
      Map<String,Object> recipient,
      Map<String,Object> transaction,
      Map<String,Object> taxSummary,
      List<Map<String,Object>> lines,
      Map<String,Object> design) {
    Map<String,Object> style=design==null?Map.of():design;
    Color primary=color(style.get("primaryColor"),ORANGE);
    Color accent=color(style.get("accentColor"),TEAL);
    Color paper=switch(String.valueOf(style.getOrDefault("surfaceStyle","WARM_PAPER"))) {
      case "CLEAN_WHITE" -> Color.WHITE;
      case "SOFT_SLATE" -> new Color(0xF1F5F9);
      default -> new Color(0xFCF9F2);
    };
    boolean showLogo=Boolean.TRUE.equals(style.getOrDefault("showLogo",Boolean.TRUE));
    String footer=safe(style.getOrDefault("footerText","Thank you for learning with us.").toString(),180);
    boolean creditNote="CREDIT_NOTE".equals(documentType);
    String heading=switch(documentType) {
      case "BILL_OF_SUPPLY" -> "BILL OF SUPPLY";
      case "CREDIT_NOTE" -> "CREDIT NOTE";
      case "DEBIT_NOTE" -> "DEBIT NOTE";
      default -> "TAX INVOICE";
    };

    BufferedImage image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_RGB);
    Graphics2D g=image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
      g.setColor(paper);g.fillRect(0,0,WIDTH,HEIGHT);
      g.setPaint(new java.awt.GradientPaint(0,0,primary,WIDTH,0,accent));g.fillRect(0,0,WIDTH,12);

      g.setColor(Color.WHITE);g.fillRoundRect(52,42,1136,180,22,22);
      int supplierX=78;
      if(showLogo) {
        g.setColor(primary);g.fillRoundRect(78,70,70,70,18,18);
        g.setColor(Color.WHITE);draw(g,Font.BOLD,23,"QE",113,113,60,primary);
        supplierX=170;
      }
      draw(g,Font.BOLD,22,value(supplier,"legalName","Legal supplier"),supplierX,83,570,NAVY);
      int supplierY=111;
      for(String line:addressLines(supplier.get("registeredAddress"),60)) {
        draw(g,Font.PLAIN,13,line,supplierX,supplierY,575,MUTED);supplierY+=20;
        if(supplierY>166)break;
      }
      String gstin=value(supplier,"gstin","");
      if(!gstin.isBlank()) draw(g,Font.BOLD,13,"GSTIN: "+gstin,supplierX,supplierY+2,570,accent);
      else draw(g,Font.PLAIN,12,"GST registration: not applicable to this document",supplierX,supplierY+2,570,MUTED);

      g.setColor(primary);drawRight(g,Font.BOLD,25,heading,1150,80,400,primary);
      drawRight(g,Font.BOLD,14,documentNumber,1150,112,450,NAVY);
      drawRight(g,Font.PLAIN,13,"Issue date: "+issueDate,1150,140,450,MUTED);
      drawRight(g,Font.PLAIN,12,"Currency: "+value(transaction,"currency","INR"),1150,165,450,MUTED);

      g.setColor(primary);g.setStroke(new BasicStroke(2));g.drawLine(52,244,1188,244);
      sectionLabel(g,"BILL TO",78,279,accent);
      sectionLabel(g,"COURSE / ORDER",650,279,accent);
      draw(g,Font.BOLD,17,value(recipient,"legalName","Customer"),78,313,495,INK);
      int buyerY=338;
      for(String line:addressLines(recipient.get("billingAddress"),64)) {
        draw(g,Font.PLAIN,12,line,78,buyerY,495,MUTED);buyerY+=19;
        if(buyerY>395)break;
      }
      String buyerGstin=value(recipient,"gstin","");
      if(!buyerGstin.isBlank()) draw(g,Font.PLAIN,12,"GSTIN: "+buyerGstin,78,407,495,MUTED);
      String beneficiary=value(recipient,"beneficiaryName","");
      draw(g,Font.BOLD,16,value(transaction,"courseTitle","Course access"),650,313,500,INK);
      String courseTypeCode=value(transaction,"courseTypeCode","").toUpperCase(Locale.ROOT);
      String courseTypeLabel=switch(courseTypeCode) {
        case "K12" -> "K–12 learning";
        case "COMPETITIVE" -> "Competitive preparation";
        case "ASSESSMENT" -> "Assessment & tests";
        default -> "";
      };
      String orderLabel="Order: "+value(transaction,"orderReference","");
      if(!courseTypeLabel.isBlank())orderLabel="Course type: "+courseTypeLabel+" · "+orderLabel;
      draw(g,Font.PLAIN,12,orderLabel,650,339,500,MUTED);
      if(!beneficiary.isBlank()) draw(g,Font.PLAIN,12,"Learner / beneficiary: "+beneficiary,650,363,500,MUTED);
      draw(g,Font.PLAIN,12,"Institution: "+value(transaction,"institutionName",""),650,387,500,MUTED);
      draw(g,Font.PLAIN,12,"Payment reference: "+value(transaction,"paymentReference",""),650,411,500,MUTED);
      String originalDocument=value(transaction,"originalDocumentNumber","");
      if(!originalDocument.isBlank())
        draw(g,Font.BOLD,12,"Original invoice: "+originalDocument,650,435,500,accent);

      int top=452;
      g.setColor(NAVY);g.fillRoundRect(52,top,1136,50,9,9);
      tableHeader(g,"DESCRIPTION",72,top+30,300);
      tableHeader(g,"SAC",475,top+30,100);
      tableHeader(g,"QTY",594,top+30,60);
      tableHeader(g,"TAXABLE VALUE",845,top+30,170);
      tableHeader(g,"TAX",1010,top+30,90);
      tableHeader(g,"TOTAL",1170,top+30,120);

      int rowY=top+50;
      if(lines==null||lines.isEmpty()) lines=List.of(Map.of("description","Course access"));
      for(int index=0;index<Math.min(lines.size(),3);index++) {
        Map<String,Object> line=lines.get(index);
        g.setColor(Color.WHITE);g.fillRect(52,rowY,1136,76);
        g.setColor(LINE);g.drawLine(52,rowY+76,1188,rowY+76);
        draw(g,Font.BOLD,14,value(line,"description","Course access"),72,rowY+27,370,INK);
        String sac=value(line,"serviceClassificationCode","");
        draw(g,Font.PLAIN,12,sac.isBlank()?"—":sac,475,rowY+28,100,MUTED);
        draw(g,Font.PLAIN,12,quantity(line.get("quantity")),594,rowY+28,60,INK);
        drawRight(g,Font.PLAIN,12,money(line.get("taxableValuePaise")),845,rowY+28,170,INK);
        drawRight(g,Font.PLAIN,12,money(line.get("taxValuePaise")),1010,rowY+28,90,INK);
        drawRight(g,Font.BOLD,12,money(line.get("lineTotalPaise")),1170,rowY+28,120,NAVY);
        long discount=number(line.get("discountValuePaise"));
        if(discount>0) draw(g,Font.PLAIN,11,"Discount: "+money(discount),72,rowY+51,370,MUTED);
        rowY+=76;
      }
      rowY=Math.max(rowY+26,690);
      g.setColor(LINE);g.drawLine(52,rowY-5,1188,rowY-5);

      sectionLabel(g,"TAX BREAKDOWN",78,rowY+28,accent);
      List<Map<String,Object>> components=asMapList(taxSummary.get("components"));
      int taxY=rowY+58;
      if(components.isEmpty()) {
        draw(g,Font.PLAIN,12,"No output GST charged under the approved tax treatment.",78,taxY,500,MUTED);
        taxY+=22;
      } else {
        for(Map<String,Object> component:components) {
          String name=value(component,"name","Tax");
          String rate=rate(component.get("rateBasisPoints"));
          draw(g,Font.PLAIN,12,name+"  "+rate,78,taxY,310,INK);
          drawRight(g,Font.PLAIN,12,money(component.get("amountPaise")),490,taxY,120,INK);
          taxY+=23;
          if(taxY>rowY+145)break;
        }
      }
      String classification=value(taxSummary,"serviceClassificationCode","");
      if(!classification.isBlank())draw(g,Font.PLAIN,11,"Service classification: "+classification,78,taxY+8,510,MUTED);

      int totalsX=650,totalsY=rowY+28;
      totalRow(g,"Listed price",money(transaction.get("listedPricePaise")),totalsX,totalsY,500,INK);totalsY+=32;
      totalRow(g,"Discount",money(transaction.get("discountValuePaise")),totalsX,totalsY,500,INK);totalsY+=32;
      totalRow(g,"Taxable value",money(transaction.get("taxableValuePaise")),totalsX,totalsY,500,INK);totalsY+=32;
      totalRow(g,"Total tax",money(transaction.get("taxValuePaise")),totalsX,totalsY,500,INK);totalsY+=41;
      g.setColor(primary);g.fillRoundRect(totalsX-13,totalsY-22,538,60,12,12);
      totalRow(g,creditNote?"TOTAL CREDIT":"TOTAL PAID",money(transaction.get("amountPaidPaise")),totalsX,totalsY+7,500,Color.WHITE);
      totalsY+=77;
      totalRow(g,"Payment status",value(transaction,"paymentStatus","CAPTURED"),totalsX,totalsY,500,accent);

      int footerY=Math.max(taxY+50,totalsY+55);
      footerY=Math.min(footerY,1485);
      g.setColor(LINE);g.drawLine(52,footerY,1188,footerY);
      draw(g,Font.BOLD,14,footer,78,footerY+33,1080,NAVY);
      String footerNote=creditNote
          ?"This credit note is linked to the original invoice and a provider-confirmed customer refund. Retain both documents for accounting and tax records."
          :"This document reflects the transaction, seller, recipient and tax details captured when the order was verified. Retain this invoice and contact the seller for correction or refund requests.";
      drawWrapped(g,Font.PLAIN,11,footerNote,78,footerY+58,1080,17,MUTED);
      g.setColor(MUTED);draw(g,Font.PLAIN,10,"QuantaEdge · Electronically generated fiscal document",78,HEIGHT-55,1080,MUTED);
      g.setColor(accent);drawRight(g,Font.BOLD,11,documentNumber,1170,HEIGHT-55,300,accent);
    } finally {g.dispose();}

    try {
      byte[] png;
      try(ByteArrayOutputStream pngOut=new ByteArrayOutputStream()) {
        if(!ImageIO.write(image,"png",pngOut))throw new IOException("PNG encoder is unavailable.");
        png=pngOut.toByteArray();
      }
      byte[] pdf;
      try(PDDocument document=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
        PDPage page=new PDPage(PDRectangle.A4);document.addPage(page);
        PDImageXObject embedded=LosslessFactory.createFromImage(document,image);
        try(PDPageContentStream stream=new PDPageContentStream(document,page)) {
          stream.drawImage(embedded,0,0,page.getMediaBox().getWidth(),page.getMediaBox().getHeight());
        }
        document.save(out);pdf=out.toByteArray();
      }
      return new RenderedFiscalDocument(pdf,png);
    } catch(IOException ex) {
      throw new IllegalStateException("Fiscal document rendering failed.",ex);
    }
  }

  private static void sectionLabel(Graphics2D g,String label,int x,int baseline,Color color) {
    draw(g,Font.BOLD,12,label,x,baseline,420,color);
  }
  private static void tableHeader(Graphics2D g,String label,int rightOrX,int baseline,int width) {
    if("TOTAL".equals(label)||"TAX".equals(label)||"TAXABLE VALUE".equals(label))
      drawRight(g,Font.BOLD,10,label,rightOrX,baseline,width,Color.WHITE);
    else draw(g,Font.BOLD,10,label,rightOrX,baseline,width,Color.WHITE);
  }
  private static void totalRow(Graphics2D g,String label,String value,int x,int baseline,int width,Color color) {
    draw(g,Font.PLAIN,13,label,x,baseline,width/2,color);
    drawRight(g,Font.BOLD,13,value,x+width,baseline,width/2,color);
  }
  private static String value(Map<String,Object> map,String key,String fallback) {
    if(map==null||map.get(key)==null)return fallback;
    return safe(String.valueOf(map.get(key)),400);
  }
  private static long number(Object raw) {
    try { return raw instanceof Number n?n.longValue():Long.parseLong(String.valueOf(raw)); }
    catch(Exception ignored) { return 0L; }
  }
  private static String money(Object raw) {
    long amount=number(raw);
    return "₹"+NumberFormat.getNumberInstance(new Locale("en","IN")).format(BigDecimal.valueOf(amount,2));
  }
  private static String rate(Object raw) {
    BigDecimal b=BigDecimal.valueOf(number(raw),2).stripTrailingZeros();
    return b.toPlainString()+"%";
  }
  private static String quantity(Object raw) {
    try { return new BigDecimal(String.valueOf(raw==null?"1":raw)).stripTrailingZeros().toPlainString(); }
    catch(Exception ignored) { return "1"; }
  }
  private static Color color(Object raw,Color fallback) {
    if(raw==null)return fallback;
    String value=String.valueOf(raw);
    if(!value.matches("#[0-9A-Fa-f]{6}"))return fallback;
    return new Color(Integer.parseInt(value.substring(1),16));
  }
  private static List<Map<String,Object>> asMapList(Object raw) {
    if(!(raw instanceof List<?> list))return List.of();
    List<Map<String,Object>> out=new ArrayList<>();
    for(Object item:list)if(item instanceof Map<?,?> map) {
      java.util.LinkedHashMap<String,Object> converted=new java.util.LinkedHashMap<>();
      map.forEach((k,v)->converted.put(String.valueOf(k),v));out.add(converted);
    }
    return out;
  }
  private static List<String> addressLines(Object raw,int max) {
    Map<String,Object> address;
    if(raw instanceof Map<?,?> map) {
      java.util.LinkedHashMap<String,Object> a=new java.util.LinkedHashMap<>();
      map.forEach((k,v)->a.put(String.valueOf(k),v));address=a;
    } else return List.of();
    List<String> lines=new ArrayList<>();
    for(String key:List.of("line1","line2","city","state","postalCode","country")) {
      String part=safe(String.valueOf(address.getOrDefault(key,"")),max);
      if(!part.isBlank()&&!part.equals("null"))lines.add(part);
    }
    return lines;
  }
  private static String safe(String raw,int max) {
    String clean=raw==null?"":raw.replaceAll("[\\p{Cntrl}]"," ").trim();
    return clean.length()>max?clean.substring(0,max):clean;
  }
  private static Font font(int style,int size,String text) {
    boolean devanagari=text!=null&&text.codePoints().anyMatch(cp->cp>=0x0900&&cp<=0x097F);
    String family=devanagari?"Noto Sans Devanagari":"SansSerif";
    Font preferred=new Font(family,style,size);
    if(preferred.canDisplayUpTo(text==null?"":text)<0)return preferred;
    Font composite=new Font(Font.DIALOG,style,size);
    if(composite.canDisplayUpTo(text==null?"":text)<0)return composite;
    throw new IllegalStateException("Installed fonts cannot display all characters in a fiscal document.");
  }
  private static void draw(Graphics2D g,int style,int size,String text,int x,int baseline,int maxWidth,Color color) {
    String safe=safe(text,500);
    Font preferred=font(style,size,safe);
    TextLayout layout=new TextLayout(safe,preferred,g.getFontRenderContext());
    Font effective=preferred;
    while(layout.getAdvance()>maxWidth&&effective.getSize()>9) {
      effective=effective.deriveFont(effective.getSize2D()-1f);
      layout=new TextLayout(safe,effective,g.getFontRenderContext());
    }
    g.setColor(color);layout.draw(g,x,baseline);
  }
  private static void drawRight(Graphics2D g,int style,int size,String text,int right,int baseline,int maxWidth,Color color) {
    String safe=safe(text,500);
    Font preferred=font(style,size,safe);
    TextLayout layout=new TextLayout(safe,preferred,g.getFontRenderContext());
    Font effective=preferred;
    while(layout.getAdvance()>maxWidth&&effective.getSize()>9) {
      effective=effective.deriveFont(effective.getSize2D()-1f);
      layout=new TextLayout(safe,effective,g.getFontRenderContext());
    }
    g.setColor(color);layout.draw(g,right-(int)Math.ceil(layout.getAdvance()),baseline);
  }
  private static void drawWrapped(Graphics2D g,int style,int size,String text,int x,int baseline,int maxWidth,int lineGap,Color color) {
    String clean=safe(text,1200);
    List<String> words=List.of(clean.split("\\s+"));
    StringBuilder line=new StringBuilder();
    int y=baseline;
    for(String word:words) {
      String candidate=line.isEmpty()?word:line+" "+word;
      int width=new TextLayout(candidate,font(style,size,candidate),g.getFontRenderContext()).getAdvance()>Integer.MAX_VALUE
          ?maxWidth:(int)new TextLayout(candidate,font(style,size,candidate),g.getFontRenderContext()).getAdvance();
      if(width>maxWidth&&!line.isEmpty()) {
        draw(g,style,size,line.toString(),x,y,maxWidth,color);y+=lineGap;line.setLength(0);line.append(word);
      } else {if(!line.isEmpty())line.append(' ');line.append(word);}
      if(y>HEIGHT-80)break;
    }
    if(!line.isEmpty()&&y<=HEIGHT-80)draw(g,style,size,line.toString(),x,y,maxWidth,color);
  }
}
