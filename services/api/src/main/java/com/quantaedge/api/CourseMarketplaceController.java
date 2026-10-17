package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Server-authoritative course marketplace and Razorpay checkout.
 *
 * The browser/mobile client receives only the public Razorpay key ID and a server-created
 * order. Course price, payment capture status and learner entitlement are verified here.
 */
@RestController
@RequestMapping("/api/v1/marketplace")
public class CourseMarketplaceController {
  private static final long ORDER_EXPIRY_MINUTES = 30L;
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final ObjectMapper objectMapper;
  private final String keyId;
  private final String keySecret;
  private final String baseUrl;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build();

  public CourseMarketplaceController(
      JdbcTemplate jdbc,
      AuthorizationService authorization,
      ObjectMapper objectMapper,
      @Value("${app.razorpay.key-id:}") String keyId,
      @Value("${app.razorpay.key-secret:}") String keySecret,
      @Value("${app.razorpay.base-url:https://api.razorpay.com/v1}") String baseUrl) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.objectMapper = objectMapper;
    this.keyId = keyId == null ? "" : keyId.trim();
    this.keySecret = keySecret == null ? "" : keySecret.trim();
    this.baseUrl = (baseUrl == null ? "https://api.razorpay.com/v1" : baseUrl.trim()).replaceAll("/+$", "");
  }

  /** Only explicitly published offers are discoverable; this is not a list of every curriculum row. */
  @GetMapping("/courses")
  public List<Map<String, Object>> courses(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    List<Map<String, Object>> offers = jdbc.queryForList("""
        select p.id as course_id, p.public_id, p.code as course_code,
          coalesce(nullif(o.title_override,''),p.display_name) as title,
          p.display_name as course_name, p.curriculum_version,
          o.description, o.price_paise, o.currency, o.status as offer_status,
          exists(select 1 from student_program_enrollment e
            where e.student_id=? and e.program_id=p.id and e.status in ('ACTIVE','PAUSED')) as has_access,
          exists(select 1 from course_purchase cp
            where cp.student_id=? and cp.course_id=p.id) as purchased
        from course_marketplace_offer o
        join curriculum_program p on p.id=o.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
        where o.status='PUBLISHED'
        order by p.display_name,p.id
        """, context.studentId(), context.studentId());
    for (Map<String, Object> offer : offers) {
      long courseId = ((Number) offer.get("course_id")).longValue();
      offer.put("subjects", jdbc.queryForList("""
          select s.code as subject_code, s.display_name as subject_name,
            count(distinct ch.id) as chapters, count(distinct l.id) as lessons
          from curriculum_program_subject ps
          join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
            and s.class_id=ps.class_id and s.active=true
          left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
            and ch.content_status='PUBLISHED'
          left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
          where ps.program_id=?
          group by s.id,s.code,s.display_name,s.sort_order
          order by s.sort_order,s.id
          """, courseId));
      offer.put("is_enrolled", Boolean.TRUE.equals(offer.get("has_access")));
    }
    return offers;
  }

  /** Create a payment order from the published server-side offer price, never a client amount. */
  @PostMapping("/courses/{courseId}/orders")
  public Map<String, Object> createOrder(
      @PathVariable long courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    requireConfigured();
    List<Map<String, Object>> rows = jdbc.queryForList("""
        select p.id, coalesce(nullif(o.title_override,''),p.display_name) as title,
          o.price_paise, o.currency, o.description
        from course_marketplace_offer o
        join curriculum_program p on p.id=o.course_id
        where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
          and o.status='PUBLISHED' and o.price_paise>0
        """, courseId);
    if (rows.isEmpty()) throw new IllegalArgumentException("This paid course is not currently available.");
    if (hasAccess(context.studentId(), courseId)) {
      throw new IllegalArgumentException("You already have access to this course.");
    }
    Map<String, Object> offer = rows.getFirst();
    long amountPaise = ((Number) offer.get("price_paise")).longValue();
    String currency = String.valueOf(offer.get("currency")).toUpperCase(Locale.ROOT);
    jdbc.update("""
        update course_payment_order set status='EXPIRED',updated_at=now()
        where student_id=? and course_id=? and status='PENDING'
          and created_at < now() - (? * interval '1 minute')
        """, context.studentId(), courseId, ORDER_EXPIRY_MINUTES);

    String receipt = "qe_" + UUID.randomUUID().toString().replace("-", "");
    long localOrderId = jdbc.queryForObject("""
        insert into course_payment_order(student_id,course_id,offer_price_paise,currency,receipt,status)
        values(?,?,?, ?,?,'CREATING')
        returning id
        """, Long.class, context.studentId(), courseId, amountPaise, currency, receipt);
    try {
      String requestJson = objectMapper.writeValueAsString(Map.of(
          "amount", amountPaise,
          "currency", currency,
          "receipt", receipt,
          "notes", Map.of(
              "quantaedge_order_id", Long.toString(localOrderId),
              "quantaedge_student_id", Long.toString(context.studentId()),
              "quantaedge_course_id", Long.toString(courseId)
          )));
      JsonNode providerOrder = razorpayRequest("POST", "/orders", requestJson);
      String providerOrderId = providerOrder.path("id").asText("");
      long providerAmount = providerOrder.path("amount").asLong(-1);
      String providerCurrency = providerOrder.path("currency").asText("");
      if (!providerOrderId.matches("order_[A-Za-z0-9]+")
          || providerAmount != amountPaise
          || !currency.equalsIgnoreCase(providerCurrency)) {
        throw new IllegalStateException("Payment provider returned an order that did not match the course offer.");
      }
      jdbc.update("""
          update course_payment_order set provider_order_id=?,status='PENDING',updated_at=now()
          where id=? and student_id=? and status='CREATING'
          """, providerOrderId, localOrderId, context.studentId());
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("orderId", providerOrderId);
      result.put("localOrderId", localOrderId);
      result.put("amount", amountPaise);
      result.put("currency", currency);
      result.put("keyId", keyId);
      result.put("courseId", courseId);
      result.put("name", "QuantaEdge");
      result.put("description", String.valueOf(offer.getOrDefault("title", "Course access")));
      return result;
    } catch (Exception exception) {
      jdbc.update("""
          update course_payment_order set status='FAILED',failure_code='ORDER_CREATE_FAILED',updated_at=now()
          where id=? and status='CREATING'
          """, localOrderId);
      if (exception instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("Payment could not be started. Please retry.", exception);
    }
  }

  /** Verify checkout signature and reconcile the payment against Razorpay before granting access. */
  @PostMapping("/orders/{localOrderId}/verify")
  @Transactional
  public Map<String, Object> verifyOrder(
      @PathVariable long localOrderId,
      @RequestBody VerifyCoursePaymentRequest request,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    requireConfigured();
    String paymentId = request == null || request.paymentId() == null ? "" : request.paymentId().trim();
    String orderId = request == null || request.orderId() == null ? "" : request.orderId().trim();
    String signature = request == null || request.signature() == null ? "" : request.signature().trim();
    if (!paymentId.matches("pay_[A-Za-z0-9]+") || signature.isBlank()) {
      throw new IllegalArgumentException("The payment confirmation is incomplete. Please retry checkout verification.");
    }
    List<Map<String, Object>> rows = jdbc.queryForList("""
        select id,student_id,course_id,offer_price_paise,currency,provider_order_id,
          provider_payment_id,status
        from course_payment_order
        where id=? and student_id=?
        for update
        """, localOrderId, context.studentId());
    if (rows.isEmpty()) throw new SecurityException("This payment order is not available to this account.");
    Map<String, Object> order = rows.getFirst();
    String state = String.valueOf(order.get("status"));
    String savedPaymentId = (String) order.get("provider_payment_id");
    long courseId = ((Number) order.get("course_id")).longValue();
    if ("VERIFIED".equals(state)) {
      if (paymentId.equals(savedPaymentId)) return verifiedResult(localOrderId, courseId);
      throw new IllegalArgumentException("This order has already been verified against a different payment.");
    }
    if (!"PENDING".equals(state)) throw new IllegalArgumentException("This payment order is no longer payable.");
    String expectedOrderId = String.valueOf(order.get("provider_order_id"));
    if (!expectedOrderId.equals(orderId)) {
      throw new IllegalArgumentException("The payment order does not match. Please reopen the course checkout.");
    }
    String expectedSignature = hmacSha256(orderId + "|" + paymentId, keySecret);
    if (!constantTimeEquals(expectedSignature, signature)) {
      throw new IllegalArgumentException("Payment verification failed. No course access was granted.");
    }

    JsonNode payment = razorpayRequest("GET", "/payments/" + paymentId, null);
    long expectedAmount = ((Number) order.get("offer_price_paise")).longValue();
    String expectedCurrency = String.valueOf(order.get("currency"));
    if (!orderId.equals(payment.path("order_id").asText(""))
        || payment.path("amount").asLong(-1) != expectedAmount
        || !expectedCurrency.equalsIgnoreCase(payment.path("currency").asText(""))
        || !"captured".equalsIgnoreCase(payment.path("status").asText(""))) {
      throw new IllegalArgumentException("The payment is not captured for this exact course order. Course access remains locked.");
    }

    jdbc.update("""
        insert into course_purchase(student_id,course_id,order_id,provider_payment_id,amount_paise,currency)
        values(?,?,?,?,?,?)
        """, context.studentId(), courseId, localOrderId, paymentId, expectedAmount, expectedCurrency);
    // Course membership is switched server-side. Existing access and learning history are retained;
    // the prior active course becomes paused and remains visible in All Courses.
    jdbc.update("""
        update student_program_enrollment set status='PAUSED',updated_at=now()
        where student_id=? and status='ACTIVE' and program_id<>?
        """, context.studentId(), courseId);
    jdbc.update("""
        insert into student_program_enrollment(student_id,program_id,status,enrolled_at,ended_at,created_at,updated_at)
        values(?,?,'ACTIVE',now(),null,now(),now())
        on conflict(student_id,program_id) do update
          set status='ACTIVE',
            enrolled_at=case when student_program_enrollment.status='ACTIVE'
              then student_program_enrollment.enrolled_at else now() end,
            ended_at=null,updated_at=now()
        """, context.studentId(), courseId);
    jdbc.update("""
        insert into student_track_enrollment(student_id,subject_id,status)
        select ?,s.id,'ACTIVE'
        from curriculum_program_subject ps
        join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
          and s.class_id=ps.class_id and s.active=true
        where ps.program_id=?
        on conflict(student_id,subject_id) do update
          set status='ACTIVE',updated_at=now()
        """, context.studentId(), courseId);
    jdbc.update("""
        update course_payment_order set status='VERIFIED',provider_payment_id=?,provider_signature=?,
          verified_at=now(),updated_at=now()
        where id=? and status='PENDING'
        """, paymentId, signature, localOrderId);
    return verifiedResult(localOrderId, courseId);
  }

  /** Admin catalogue is deliberately separate from the student purchase surface. */
  @GetMapping("/admin/courses")
  public List<Map<String, Object>> adminCourses(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requireAdmin(context);
    return jdbc.queryForList("""
        select p.id as course_id,p.code,p.display_name,p.curriculum_version,p.status as course_status,
          o.title_override,o.description,o.price_paise,o.currency,o.status as offer_status,
          o.updated_at
        from curriculum_program p
        left join course_marketplace_offer o on o.course_id=p.id
        where p.status='ACTIVE' and p.code<>'GENERAL'
        order by p.display_name,p.id
        """);
  }

  /** Publish/update offer metadata and server-side price through an explicit Admin action. */
  @PutMapping("/admin/courses/{courseId}")
  public Map<String, Object> saveOffer(
      @PathVariable long courseId,
      @RequestBody SaveCourseOfferRequest request,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireAdmin(context);
    if (request == null || request.pricePaise() < 0L) {
      throw new IllegalArgumentException("Enter a valid course price.");
    }
    String currency = request.currency() == null ? "INR" : request.currency().trim().toUpperCase(Locale.ROOT);
    if (!currency.matches("[A-Z]{3}") || !"INR".equals(currency)) {
      throw new IllegalArgumentException("Course checkout currently supports INR only.");
    }
    String status = request.status() == null ? "DRAFT" : request.status().trim().toUpperCase(Locale.ROOT);
    if (!List.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(status)) {
      throw new IllegalArgumentException("Choose a valid marketplace status.");
    }
    String title = request.title() == null ? "" : request.title().trim();
    String description = request.description() == null ? "" : request.description().trim();
    if (title.length() > 200 || description.length() > 4000) {
      throw new IllegalArgumentException("The course title or description is too long.");
    }
    Integer count = jdbc.queryForObject("""
        select count(*) from curriculum_program where id=? and status='ACTIVE' and code<>'GENERAL'
        """, Integer.class, courseId);
    if (count == null || count != 1) throw new IllegalArgumentException("Choose an active course.");
    jdbc.update("""
        insert into course_marketplace_offer(
          course_id,title_override,description,price_paise,currency,status,created_by_staff_id,updated_by_staff_id
        ) values(?,?,?,?,?,?,?,?)
        on conflict(course_id) do update set
          title_override=excluded.title_override,description=excluded.description,
          price_paise=excluded.price_paise,currency=excluded.currency,status=excluded.status,
          updated_by_staff_id=excluded.updated_by_staff_id,updated_at=now()
        """, courseId, title.isBlank() ? null : title, description.isBlank() ? null : description,
        request.pricePaise(), currency, status, context.staffId(), context.staffId());
    return jdbc.queryForMap("""
        select p.id as course_id,coalesce(nullif(o.title_override,''),p.display_name) as title,
          o.description,o.price_paise,o.currency,o.status as offer_status,o.updated_at
        from course_marketplace_offer o join curriculum_program p on p.id=o.course_id
        where o.course_id=?
        """, courseId);
  }

  private boolean hasAccess(long studentId, long courseId) {
    Boolean value = jdbc.queryForObject("""
        select exists(
          select 1 from student_program_enrollment e
          where e.student_id=? and e.program_id=? and e.status in ('ACTIVE','PAUSED')
          union all
          select 1 from course_purchase p where p.student_id=? and p.course_id=?
        )
        """, Boolean.class, studentId, courseId, studentId, courseId);
    return Boolean.TRUE.equals(value);
  }

  private Map<String, Object> verifiedResult(long localOrderId, long courseId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "VERIFIED");
    result.put("entitled", true);
    result.put("orderId", localOrderId);
    result.put("courseId", courseId);
    return result;
  }

  private void requireConfigured() {
    if (keyId.isBlank() || keySecret.isBlank()) {
      throw new IllegalStateException("Course checkout is not available right now. Please try again later.");
    }
  }

  private JsonNode razorpayRequest(String method, String path, String body) {
    requireConfigured();
    try {
      String basic = Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
          .timeout(java.time.Duration.ofSeconds(15))
          .header("Authorization", "Basic " + basic)
          .header("Accept", "application/json");
      if (body == null) {
        builder.GET();
      } else {
        builder.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      }
      HttpResponse<String> response = httpClient.send(builder.method(method, body == null
          ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Payment provider could not complete the request (HTTP "
            + response.statusCode() + ").");
      }
      return objectMapper.readTree(response.body());
    } catch (IllegalStateException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Payment provider could not be reached. Please retry.", exception);
    }
  }

  static String hmacSha256(String data, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Payment verification is unavailable.", exception);
    }
  }

  private static boolean constantTimeEquals(String expected, String supplied) {
    if (expected == null || supplied == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        supplied.getBytes(StandardCharsets.US_ASCII));
  }

  public record VerifyCoursePaymentRequest(String paymentId, String orderId, String signature) {}
  public record SaveCourseOfferRequest(String title, String description, long pricePaise, String currency, String status) {}
}
