package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BillingCustomerProfileControllerTest {
  @Test
  void acceptsIndividualWithNoGstinAndDefaultsAddressToEmpty() {
    var draft=BillingCustomerProfileController.validateProfile(Map.of(
        "billingType","INDIVIDUAL","legalName","A Student"));
    assertEquals("INDIVIDUAL",draft.billingType());
    assertEquals("A Student",draft.legalName());
    assertEquals(null,draft.gstin());
    assertEquals(Map.of(),draft.billingAddress());
  }

  @Test
  void acceptsBusinessWithMatchingGstinStateAndRequiredAddress() {
    var draft=BillingCustomerProfileController.validateProfile(Map.of(
        "billingType","BUSINESS","legalName","Example Learning Pvt Ltd",
        "gstin","10ABCDE1234F1Z5","gstStateCode","10",
        "billingAddress",Map.of("line1","Main Road","city","Patna","state","Bihar","postalCode","800001"),
        "contactEmail","finance@example.in"));
    assertEquals("BUSINESS",draft.billingType());
    assertEquals("10ABCDE1234F1Z5",draft.gstin());
    assertEquals("10",draft.gstStateCode());
  }

  @Test
  void rejectsGstinWhoseStatePrefixDoesNotMatch() {
    assertThrows(ResponseStatusException.class,()->BillingCustomerProfileController.validateProfile(Map.of(
        "billingType","INDIVIDUAL","legalName","Example","gstin","10ABCDE1234F1Z5","gstStateCode","27")));
  }

  @Test
  void rejectsBusinessBillingWithoutACompleteAddress() {
    assertThrows(ResponseStatusException.class,()->BillingCustomerProfileController.validateProfile(Map.of(
        "billingType","BUSINESS","legalName","Example Pvt Ltd","billingAddress",Map.of("city","Patna"))));
  }

  @Test
  void savedIndividualAddressRequiresCompletePostalFields() {
    assertThrows(ResponseStatusException.class,()->BillingCustomerProfileController.validateSavedAddress(Map.of(
        "billingType","INDIVIDUAL","legalName","Example","billingAddress",Map.of("line1","Main Road","city","Patna"))));
  }

  @Test
  void savedIndividualAddressDefaultsCountryAndAcceptsCompletePostalFields() {
    var draft=BillingCustomerProfileController.validateSavedAddress(Map.of(
        "billingType","INDIVIDUAL","legalName","Example",
        "billingAddress",Map.of("line1","Main Road","city","Patna","state","Bihar","postalCode","800001")));
    assertEquals("India",draft.billingAddress().get("country"));
    assertEquals("Main Road",draft.billingAddress().get("line1"));
  }

  @Test
  void rejectsInvalidGstinAndEmail() {
    assertThrows(ResponseStatusException.class,()->BillingCustomerProfileController.validateProfile(Map.of(
        "billingType","INDIVIDUAL","legalName","Example","gstin","INVALID")));
    assertThrows(ResponseStatusException.class,()->BillingCustomerProfileController.validateProfile(Map.of(
        "billingType","INDIVIDUAL","legalName","Example","contactEmail","not-an-email")));
  }
}
