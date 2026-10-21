package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Purchaser-owned billing identity for course receipts and fiscal-document snapshots. */
@RestController
@RequestMapping("/api/v1/marketplace")
public class BillingCustomerProfileController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final ObjectMapper objectMapper;

  public BillingCustomerProfileController(JdbcTemplate jdbc,AuthorizationService authorization,ObjectMapper objectMapper) {
    this.jdbc=jdbc;this.authorization=authorization;this.objectMapper=objectMapper;
  }

  @GetMapping("/billing-profile")
  public Map<String,Object> studentProfile(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    Long userId=jdbc.queryForObject("""
      select user_account_id from student where id=? and active=true
      """,Long.class,context.studentId());
    if(userId==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A linked purchaser identity is required for billing details.");
    return profile(userId);
  }

  @PutMapping("/billing-profile")
  @Transactional
  public Map<String,Object> updateStudentProfile(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    Long userId=jdbc.queryForObject("""
      select user_account_id from student where id=? and active=true
      """,Long.class,context.studentId());
    if(userId==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A linked purchaser identity is required for billing details.");
    save(userId,userId,body);
    return profile(userId);
  }

  @GetMapping("/family/billing-profile")
  public Map<String,Object> parentProfile(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    return profile(context.userId());
  }

  @PutMapping("/family/billing-profile")
  @Transactional
  public Map<String,Object> updateParentProfile(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    save(context.userId(),context.userId(),body);
    return profile(context.userId());
  }

  private void save(long userId,long actorUserId,Map<String,Object> body) {
    ProfileDraft draft=validateProfile(body);
    if(!exists("select exists(select 1 from user_account where id=? and active=true)",userId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"An active purchaser account is required.");
    }
    boolean created=!exists("select exists(select 1 from billing_customer_profile where user_account_id=?)",userId);
    jdbc.update("""
      insert into billing_customer_profile(
        user_account_id,billing_type,legal_name,gstin,gst_state_code,billing_address,
        contact_email,contact_phone,version,created_at,updated_at
      ) values(?,?,?,?,?,?::jsonb,?,?,1,now(),now())
      on conflict(user_account_id) do update set
        billing_type=excluded.billing_type,legal_name=excluded.legal_name,gstin=excluded.gstin,
        gst_state_code=excluded.gst_state_code,billing_address=excluded.billing_address,
        contact_email=excluded.contact_email,contact_phone=excluded.contact_phone,
        version=billing_customer_profile.version+1,updated_at=now()
      """,userId,draft.billingType(),draft.legalName(),draft.gstin(),draft.gstStateCode(),
        json(draft.billingAddress()),draft.contactEmail(),draft.contactPhone());
    Long version=jdbc.queryForObject("select version from billing_customer_profile where user_account_id=?",Long.class,userId);
    List<String> fields=List.of("billingType","legalName","gstin","gstStateCode","billingAddress","contactEmail","contactPhone");
    jdbc.update("""
      insert into billing_customer_profile_event(
        user_account_id,event_type,profile_version,actor_user_account_id,changed_fields
      ) values(?,?,?,?,?::jsonb)
      """,userId,created?"CREATED":"UPDATED",version,actorUserId,json(fields));
  }

  private Map<String,Object> profile(long userId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select u.id as user_account_id,coalesce(nullif(p.legal_name,''),u.display_name) as legal_name,
        coalesce(p.billing_type,'INDIVIDUAL') as billing_type,p.gstin,p.gst_state_code,
        coalesce(p.billing_address,'{}'::jsonb)::text as billing_address_json,
        coalesce(p.contact_email,u.contact_email,u.email) as contact_email,
        coalesce(p.contact_phone,u.mobile_e164) as contact_phone,
        coalesce(p.version,0) as profile_version,(p.user_account_id is not null) as saved
      from user_account u left join billing_customer_profile p on p.user_account_id=u.id
      where u.id=? and u.active=true
      """,userId);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Billing profile owner is unavailable.");
    Map<String,Object> result=new LinkedHashMap<>(rows.getFirst());
    Object raw=result.remove("billing_address_json");
    result.put("billingAddress",parseAddress(raw));
    return result;
  }

  static ProfileDraft validateProfile(Map<String,Object> body) {
    if(body==null) throw bad("Billing details are required.");
    String type=text(body.get("billingType"),"INDIVIDUAL").toUpperCase(Locale.ROOT);
    if(!List.of("INDIVIDUAL","BUSINESS").contains(type)) throw bad("Billing type must be INDIVIDUAL or BUSINESS.");
    String legalName=requiredText(body.get("legalName"),"Legal or billing name");
    if(legalName.length()>240) throw bad("Legal or billing name cannot exceed 240 characters.");
    String gstin=optionalText(body.get("gstin"));
    if(gstin!=null) {
      gstin=gstin.replaceAll("\\s","").toUpperCase(Locale.ROOT);
      if(!gstin.matches("[0-9A-Z]{15}")) throw bad("GSTIN must contain 15 alphanumeric characters.");
    }
    String stateCode=optionalText(body.get("gstStateCode"));
    if(stateCode!=null&&!stateCode.matches("[0-9]{2}")) throw bad("GST state code must contain two digits.");
    if(gstin!=null&&(stateCode==null||!gstin.startsWith(stateCode))) {
      throw bad("GSTIN prefix must match the supplied GST state code.");
    }
    Map<String,Object> address=cleanAddress(body.get("billingAddress"));
    if("BUSINESS".equals(type)) {
      for(String key:List.of("line1","city","state","postalCode")) {
        if(optionalText(address.get(key))==null) throw bad("Business billing address requires "+key+".");
      }
    }
    String email=optionalText(body.get("contactEmail"));
    if(email!=null&&(email.length()>320||!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
      throw bad("Enter a valid billing contact email.");
    }
    String phone=optionalText(body.get("contactPhone"));
    if(phone!=null&&(phone.length()>40||!phone.matches("[+0-9() .-]{5,40}"))) throw bad("Enter a valid billing contact phone.");
    return new ProfileDraft(type,legalName,gstin,stateCode,address,email,phone);
  }

  private static Map<String,Object> cleanAddress(Object raw) {
    if(raw==null) return Map.of();
    if(!(raw instanceof Map<?,?> input)) throw bad("Billing address must be an object.");
    Map<String,Object> output=new LinkedHashMap<>();
    for(String key:List.of("line1","line2","city","state","postalCode","country")) {
      String value=optionalText(input.get(key));
      if(value!=null) {
        int max=key.equals("line1")||key.equals("line2")?180: key.equals("postalCode")?16:80;
        if(value.length()>max) throw bad("Billing address field "+key+" is too long.");
        output.put(key,value);
      }
    }
    return output;
  }

  private Map<String,Object> parseAddress(Object raw) {
    if(raw==null) return Map.of();
    try { return objectMapper.readValue(String.valueOf(raw),new TypeReference<Map<String,Object>>(){}); }
    catch(Exception ex) { return Map.of(); }
  }

  private String json(Object value) {
    try{return objectMapper.writeValueAsString(value);}
    catch(JsonProcessingException ex){throw new IllegalArgumentException("Billing details could not be serialized.",ex);}
  }
  private boolean exists(String sql,Object... args){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));}
  private static String requiredText(Object raw,String label) {
    String value=raw==null?"":String.valueOf(raw).trim();
    if(value.isBlank()) throw bad(label+" is required.");
    return value;
  }
  private static String text(Object raw,String fallback){String value=optionalText(raw);return value==null?fallback:value;}
  private static String optionalText(Object raw){if(raw==null)return null;String value=String.valueOf(raw).trim();return value.isBlank()?null:value;}
  private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}

  record ProfileDraft(String billingType,String legalName,String gstin,String gstStateCode,
      Map<String,Object> billingAddress,String contactEmail,String contactPhone) {}
}
