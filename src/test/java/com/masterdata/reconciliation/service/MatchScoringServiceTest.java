package com.masterdata.reconciliation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterdata.reconciliation.domain.model.NormalizedRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchScoringServiceTest {
    private final MatchScoringService service = new MatchScoringService();

    @Test
    void exactIdentifierAndStrongNameCanAutoMatch() throws Exception {
        var left = record("Jane Smith", "jane@example.com", "+14155550100", "42 main st", "94105");
        var right = record("Jane Smith", "jane@example.com", null, "42 main st", "94105");
        var result = service.score(left, right);
        assertThat(result.decision()).isEqualTo("AUTO_MATCH");
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.comparableFields()).isEqualTo(3);
    }

    @Test
    void conflictingEmailAndPhoneAlwaysPreventAutomaticMerge() throws Exception {
        var left = record("Jane Smith", "jane@example.com", "+14155550100", "42 main st", "94105");
        var right = record("Jane Smith", "other@example.com", "+14155550999", "42 main st", "94105");
        var result = service.score(left, right);
        assertThat(result.contradiction()).isTrue();
        assertThat(result.decision()).isEqualTo("REVIEW");
    }

    @Test
    void fewerThanTwoComparableFieldsIsInsufficient() throws Exception {
        var left = record("Jane Smith", null, null, null, null);
        var right = record("Jane Smith", null, null, null, null);
        assertThat(service.score(left, right).decision()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    private NormalizedRecord record(String name, String email, String phone, String address, String postal) throws Exception {
        UUID revision = UUID.randomUUID();
        var raw = new ObjectMapper().readTree("""
                {"fullName":%s,"email":%s,"phone":%s,"address":%s,"postalCode":%s}
                """.formatted(json(name), json(email), json(phone), json(address), json(postal)));
        String[] names = name == null ? new String[0] : name.toLowerCase().split(" ");
        return new NormalizedRecord(revision, UUID.randomUUID(), "CRM", revision.toString(),
                name == null ? null : name.toLowerCase(), names.length == 0 ? null : names[0],
                names.length == 0 ? null : names[names.length - 1], "S530", email, phone,
                phone == null ? null : phone.substring(Math.max(0, phone.length() - 7)), address, postal,
                address == null ? null : address.split(" ")[0], Instant.now(), Instant.now(), raw);
    }

    private String json(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
