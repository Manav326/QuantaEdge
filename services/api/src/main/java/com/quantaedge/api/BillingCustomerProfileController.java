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

  @GetMapping("/billing-addresses")
  public List<Map<String,Object>> studentAddresses(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    return addresses(studentAccountId(context));
  }

  @PostMapping("/billing-addresses")
  @Transactional
  public List<Map<String,Object>> createStudentAddress(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    long userId=studentAccountId(context);
    saveAddress(userId,userId,null,body);
    return addresses(userId);
  }

  @PutMapping("/billing-addresses/{addressId}")
  @Transactional
  public List<Map<String,Object>> updateStudentAddress(@PathVariable long addressId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    long userId=studentAccountId(context);
    saveAddress(userId,userId,addressId,body);
    return addresses(userId);
  }

  @PutMapping("/billing-addresses/{addressId}/default")
  @Transactional
  public List<Map<String,Object>> defaultStudentAddress(@PathVariable long addressId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    long userId=studentAccountId(context);
    setDefaultAddress(userId,userId,addressId);
    return addresses(userId);
  }

  @DeleteMapping("/billing-addresses/{addressId}")
  @Transactional
  public List<Map<String,Object>> deleteStudentAddress(@PathVariable long addressId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    long userId=studentAccountId(context);
    deleteAddress(userId,userId,addressId);
    return addresses(userId);
  }

  @GetMapping("/family/billing-addresses")
  public List<Map<String,Object>> parentAddresses(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    return addresses(context.userId());
  }

  @PostMapping("/family/billing-addresses")
  @Transactional
  public List<Map<String,Object>> createParentAddress(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    saveAddress(context.userId(),context.userId(),null,body);
    return addresses(context.userId());
  }

  @PutMapping("/family/billing-addresses/{addressId}")
  @Transactional
  public List<Map<String,Object>> updateParentAddress(@PathVariable long addressId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    saveAddress(context.userId(),context.userId(),addressId,body);
    return addresses(context.userId());
  }

  @PutMapping("/family/billing-addresses/{addressId}/default")
  @Transactional
  public List<Map<String,Object>> defaultParentAddress(@PathVariable long addressId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    setDefaultAddress(context.userId(),context.userId(),addressId);
    return addresses(context.userId());
  }

  @DeleteMapping("/family/billing-addresses/{addressId}")
  @Transactional
  public List<Map<String,Object>> deleteParentAddress(@PathVariable long addressId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    deleteAddress(context.userId(),context.userId(),addressId);
    return addresses(context.userId());
  }

  private long studentAccountId(AuthContext context) {
    Long userId=jdbc.queryForObject(
        "select user_account_id from student where id=? and active=true",Long.class,context.studentId());
    if(userId==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
        "A linked purchaser identity is required for billing details.");
    return userId;
  }

  private List<Map<String,Object>> addresses(long userId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,label,billing_type,legal_name,gstin,gst_state_code,
        billing_address::text as billing_address_json,contact_email,contact_phone,
        is_default,version,created_at,updated_at
      from billing_customer_address where user_account_id=? and active=true
      order by is_default desc,lower(label),id
      """,userId);
    List<Map<String,Object>> result=new ArrayList<>(rows.size());
    for(Map<String,Object> row:rows) {
      Map<String,Object> item=new LinkedHashMap<>(row);
      item.put("is_default",Boolean.TRUE.equals(item.get("is_default")));
      Object raw=item.remove("billing_address_json");
      item.put("billingAddress",parseAddress(raw));
      result.add(item);
    }
    return List.copyOf(result);
  }

  private void saveAddress(long userId,long actorUserId,Long addressId,Map<String,Object> body) {
    ProfileDraft draft=validateSavedAddress(body);
    String label=text(body==null?null:body.get("label"),"Billing address");
    if(label.length()>80) throw bad("Address label cannot exceed 80 characters.");
    if(!exists("select exists(select 1 from user_account where id=? and active=true)",userId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"An active purchaser account is required.");
    }
    Integer countValue=jdbc.queryForObject(
        "select count(*) from billing_customer_address where user_account_id=? and active=true",
        Integer.class,userId);
    int count=countValue==null?0:countValue;
    boolean wasDefault=false;
    if(addressId!=null) {
      List<Boolean> defaults=jdbc.queryForList("""
        select is_default from billing_customer_address
        where id=? and user_account_id=? and active=true
        """,Boolean.class,addressId,userId);
      if(defaults.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Billing address was not found.");
      wasDefault=Boolean.TRUE.equals(defaults.getFirst());
    } else if(count>=20) {
      throw bad("You can save up to 20 billing addresses.");
    }
    Object requestedDefault=body==null?null:body.get("isDefault");
    boolean makeDefault=count==0 || Boolean.TRUE.equals(requestedDefault) || wasDefault;
    String addressJson=json(draft.billingAddress());
    if(makeDefault) jdbc.update("""
      update billing_customer_address set is_default=false,version=version+1,updated_at=now()
      where user_account_id=? and active=true and is_default=true and (?::bigint is null or id<>?)
      """,userId,addressId,addressId);
    long savedId;
    if(addressId==null) {
      savedId=jdbc.queryForObject("""
        insert into billing_customer_address(
          user_account_id,label,billing_type,legal_name,gstin,gst_state_code,billing_address,
          contact_email,contact_phone,is_default,active,version,created_at,updated_at
        ) values(?,?,?,?,?,?,?::jsonb,?,?,?,true,1,now(),now())
        returning id
        """,Long.class,userId,label,draft.billingType(),draft.legalName(),draft.gstin(),draft.gstStateCode(),
          addressJson,draft.contactEmail(),draft.contactPhone(),makeDefault);
    } else {
      int changed=jdbc.update("""
        update billing_customer_address set label=?,billing_type=?,legal_name=?,gstin=?,gst_state_code=?,
          billing_address=?::jsonb,contact_email=?,contact_phone=?,is_default=?,version=version+1,updated_at=now()
        where id=? and user_account_id=? and active=true
        """,label,draft.billingType(),draft.legalName(),draft.gstin(),draft.gstStateCode(),
          addressJson,draft.contactEmail(),draft.contactPhone(),makeDefault,addressId,userId);
      if(changed!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Billing address was not found.");
      savedId=addressId;
    }
    if(makeDefault) {
      save(userId,actorUserId,profileBody(draft),false);
    }
    Long version=jdbc.queryForObject(
        "select version from billing_customer_address where id=? and user_account_id=?",
        Long.class,savedId,userId);
    jdbc.update("""
      insert into billing_customer_address_event(
        address_id,user_account_id,event_type,address_version,actor_user_account_id,changed_fields
      ) values(?,?,?,?,?,?::jsonb)
      """,savedId,userId,addressId==null?"CREATED":makeDefault&&!wasDefault?"DEFAULT_CHANGED":"UPDATED",
        version,actorUserId,json(List.of("label","billingType","legalName","gstin","gstStateCode",
          "billingAddress","contactEmail","contactPhone")));
  }

  private ProfileDraft validateSavedAddress(Map<String,Object> body) {
    ProfileDraft draft=validateProfile(body);
    for(String key:List.of("line1","city","state","postalCode")) {
      if(optionalText(draft.billingAddress().get(key))==null) {
        throw bad("Billing address requires "+key+".");
      }
    }
    Map<String,Object> address=new LinkedHashMap<>(draft.billingAddress());
    address.putIfAbsent("country","India");
    return new ProfileDraft(draft.billingType(),draft.legalName(),draft.gstin(),
        draft.gstStateCode(),address,draft.contactEmail(),draft.contactPhone());
  }

  private Map<String,Object> profileBody(ProfileDraft draft) {
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("billingType",draft.billingType());body.put("legalName",draft.legalName());
    body.put("gstin",draft.gstin());body.put("gstStateCode",draft.gstStateCode());
    body.put("billingAddress",draft.billingAddress());body.put("contactEmail",draft.contactEmail());
    body.put("contactPhone",draft.contactPhone());
    return body;
  }

  private Map<String,Object> profileBodyForAddress(long userId,long addressId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select billing_type,legal_name,gstin,gst_state_code,billing_address::text as billing_address_json,
        contact_email,contact_phone
      from billing_customer_address
      where id=? and user_account_id=? and active=true
      """,addressId,userId);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Billing address was not found.");
    Map<String,Object> row=rows.getFirst();
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("billingType",row.get("billing_type"));body.put("legalName",row.get("legal_name"));
    body.put("gstin",row.get("gstin"));body.put("gstStateCode",row.get("gst_state_code"));
    body.put("billingAddress",parseAddress(row.get("billing_address_json")));
    body.put("contactEmail",row.get("contact_email"));body.put("contactPhone",row.get("contact_phone"));
    return body;
  }

  private void setDefaultAddress(long userId,long actorUserId,long addressId) {
    if(!exists("select exists(select 1 from billing_customer_address where id=? and user_account_id=? and active=true)",
        addressId,userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Billing address was not found.");
    jdbc.update("""
      update billing_customer_address set is_default=false,version=version+1,updated_at=now()
      where user_account_id=? and active=true and is_default=true and id<>?
      """,userId,addressId);
    jdbc.update("""
      update billing_customer_address set is_default=true,version=version+1,updated_at=now()
      where id=? and user_account_id=? and active=true
      """,addressId,userId);
    save(userId,actorUserId,profileBodyFromDefaultAddress(userId,addressId),false);
    Long version=jdbc.queryForObject(
        "select version from billing_customer_address where id=? and user_account_id=?",
        Long.class,addressId,userId);
    jdbc.update("""
      insert into billing_customer_address_event(
        address_id,user_account_id,event_type,address_version,actor_user_account_id,changed_fields
      ) values(?,?, 'DEFAULT_CHANGED', ?, ?, '["isDefault"]'::jsonb)
      """,addressId,userId,version,actorUserId);
  }

  private Map<String,Object> profileBodyFromDefaultAddress(long userId,long addressId) {
    return profileBodyForAddress(userId,addressId);
  }

  private void deleteAddress(long userId,long actorUserId,long addressId) {
    List<Boolean> defaults=jdbc.queryForList("""
      select is_default from billing_customer_address
      where id=? and user_account_id=? and active=true
      """,Boolean.class,addressId,userId);
    if(defaults.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Billing address was not found.");
    boolean wasDefault=Boolean.TRUE.equals(defaults.getFirst());
    jdbc.update("""
      update billing_customer_address set active=false,is_default=false,version=version+1,
        deleted_at=now(),updated_at=now()
      where id=? and user_account_id=? and active=true
      """,addressId,userId);
    Long version=jdbc.queryForObject(
        "select version from billing_customer_address where id=? and user_account_id=?",
        Long.class,addressId,userId);
    jdbc.update("""
      insert into billing_customer_address_event(
        address_id,user_account_id,event_type,address_version,actor_user_account_id,changed_fields
      ) values(?,?, 'DEACTIVATED', ?, ?, '["active","isDefault"]'::jsonb)
      """,addressId,userId,version,actorUserId);
    if(wasDefault) {
      List<Long> remaining=jdbc.queryForList("""
        select id from billing_customer_address where user_account_id=? and active=true
        order by updated_at desc,id desc limit 1
        """,Long.class,userId);
      if(!remaining.isEmpty()) setDefaultAddress(userId,actorUserId,remaining.getFirst());
      else clearLegacyAddress(userId,actorUserId);
    }
  }

  private void syncDefaultAddressFromLegacyProfile(long userId,long actorUserId,ProfileDraft draft) {
    if(optionalText(draft.billingAddress().get("line1"))==null
        || optionalText(draft.billingAddress().get("city"))==null
        || optionalText(draft.billingAddress().get("state"))==null
        || optionalText(draft.billingAddress().get("postalCode"))==null) return;
    List<Long> defaults=jdbc.queryForList("""
      select id from billing_customer_address where user_account_id=? and active=true and is_default=true limit 1
      """,Long.class,userId);
    long addressId;
    String addressJson=json(draft.billingAddress());
    if(defaults.isEmpty()) {
      jdbc.update("""
        update billing_customer_address set is_default=false,version=version+1,updated_at=now()
        where user_account_id=? and active=true
        """,userId);
      addressId=jdbc.queryForObject("""
        insert into billing_customer_address(
          user_account_id,label,billing_type,legal_name,gstin,gst_state_code,billing_address,
          contact_email,contact_phone,is_default,active,version,created_at,updated_at
        ) values(?,?,?,?,?,?,?::jsonb,?,?,true,true,1,now(),now()) returning id
        """,Long.class,userId,"Billing address",draft.billingType(),draft.legalName(),draft.gstin(),
          draft.gstStateCode(),addressJson,draft.contactEmail(),draft.contactPhone());
    } else {
      addressId=defaults.getFirst();
      jdbc.update("""
        update billing_customer_address set billing_type=?,legal_name=?,gstin=?,gst_state_code=?,
          billing_address=?::jsonb,contact_email=?,contact_phone=?,version=version+1,updated_at=now()
        where id=? and user_account_id=? and active=true
        """,draft.billingType(),draft.legalName(),draft.gstin(),draft.gstStateCode(),addressJson,
          draft.contactEmail(),draft.contactPhone(),addressId,userId);
    }
    Long version=jdbc.queryForObject(
        "select version from billing_customer_address where id=? and user_account_id=?",
        Long.class,addressId,userId);
    jdbc.update("""
      insert into billing_customer_address_event(
        address_id,user_account_id,event_type,address_version,actor_user_account_id,changed_fields
      ) values(?,?, 'UPDATED', ?, ?, '["billingType","legalName","gstin","gstStateCode","billingAddress","contactEmail","contactPhone"]'::jsonb)
      """,addressId,userId,version,actorUserId);
  }

  private void clearLegacyAddress(long userId,long actorUserId) {
    int changed=jdbc.update("""
      update billing_customer_profile set billing_address='{}'::jsonb,version=version+1,updated_at=now()
      where user_account_id=?
      """,userId);
    if(changed==1) {
      Long version=jdbc.queryForObject(
          "select version from billing_customer_profile where user_account_id=?",Long.class,userId);
      jdbc.update("""
        insert into billing_customer_profile_event(user_account_id,event_type,profile_version,actor_user_account_id,changed_fields)
        values(?,'UPDATED',?,?,'["billingAddress"]'::jsonb)
        """,userId,version,actorUserId);
    }
  }

  private void save(long userId,long actorUserId,Map<String,Object> body) {
    save(userId,actorUserId,body,true);
  }

  private void save(long userId,long actorUserId,Map<String,Object> body,boolean syncAddressBook) {
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
    if(syncAddressBook) syncDefaultAddressFromLegacyProfile(userId,actorUserId,draft);
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
