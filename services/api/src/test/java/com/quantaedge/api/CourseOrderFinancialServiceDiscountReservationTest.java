package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CourseOrderFinancialServiceDiscountReservationTest {
  @Test
  void reservesOnlyTheServerCalculatedDiscountForTheExactOffer() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    doReturn(List.of(approvedOffer())).when(jdbc).queryForList(anyString(),any(Object[].class));
    CourseOrderFinancialService service=service(jdbc);

    service.reserveDiscountRedemption(5L,99L,7L,20L,"save10",10_000L,1_000L);

    verify(jdbc,times(1)).update(contains("insert into course_discount_redemption"),any(Object[].class));
  }

  @Test
  void refusesAClientOrStoredAmountThatDiffersFromTheApprovedRule() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    doReturn(List.of(approvedOffer())).when(jdbc).queryForList(anyString(),any(Object[].class));
    CourseOrderFinancialService service=service(jdbc);

    IllegalStateException error=assertThrows(IllegalStateException.class,
        ()->service.reserveDiscountRedemption(5L,99L,7L,20L,"SAVE10",10_000L,1_001L));

    assertTrue(error.getMessage().contains("does not match"));
    verify(jdbc,never()).update(anyString(),any(Object[].class));
  }

  @Test
  void refusesToReserveAUseWhenTheApprovedRuleNoLongerExists() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    doReturn(List.of()).when(jdbc).queryForList(anyString(),any(Object[].class));
    CourseOrderFinancialService service=service(jdbc);

    IllegalStateException error=assertThrows(IllegalStateException.class,
        ()->service.reserveDiscountRedemption(5L,99L,7L,20L,"SAVE10",10_000L,1_000L));

    assertTrue(error.getMessage().contains("no longer active"));
    verify(jdbc,never()).update(anyString(),any(Object[].class));
  }

  private CourseOrderFinancialService service(JdbcTemplate jdbc) {
    return new CourseOrderFinancialService(
        jdbc,new ObjectMapper(),mock(FiscalDocumentRenderer.class),mock(FiscalDocumentStorage.class));
  }

  private Map<String,Object> approvedOffer() {
    Map<String,Object> row=new HashMap<>();
    row.put("id",5L);
    row.put("institution_offer_id",20L);
    row.put("code","SAVE10");
    row.put("currency","INR");
    row.put("discount_type","PERCENTAGE");
    row.put("percentage_basis_points",1_000);
    row.put("flat_amount_paise",null);
    row.put("max_discount_paise",null);
    row.put("min_listed_price_paise",0L);
    row.put("max_redemptions",null);
    row.put("max_redemptions_per_purchaser",null);
    return row;
  }
}
