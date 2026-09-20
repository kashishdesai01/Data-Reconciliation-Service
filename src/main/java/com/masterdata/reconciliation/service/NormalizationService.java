package com.masterdata.reconciliation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.masterdata.reconciliation.domain.model.NormalizedRecord;
import com.masterdata.reconciliation.config.ReconciliationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class NormalizationService {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NAME_PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern ADDRESS_PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N}\\s#-]");
    private static final Pattern STREET_NUMBER = Pattern.compile("^\\s*(\\d+[a-zA-Z]?)\\b");
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ReconciliationProperties properties;
    private final PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();

    public NormalizationService(JdbcClient jdbc, ObjectMapper objectMapper, ReconciliationProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void normalizeClaimed(UUID runId) {
        Integer version = jdbc.sql("""
                SELECT rs.normalization_version FROM match_runs mr
                JOIN rule_sets rs ON rs.id = mr.ruleset_id WHERE mr.id = :runId
                """).param("runId", runId).query(Integer.class).single();
        var revisions = jdbc.sql("""
                SELECT sri.source_revision_id FROM match_run_items sri
                WHERE sri.run_id = :runId ORDER BY sri.source_revision_id
                """).param("runId", runId).query(UUID.class).list();
        for (UUID revisionId : revisions) normalize(revisionId, version);
    }

    public NormalizedRecord normalize(UUID revisionId, int version) {
        var existing = find(revisionId, version);
        if (existing != null) return existing;

        var source = jdbc.sql("""
                SELECT sr.id source_record_id, sr.source_system, sr.source_record_id source_record_key,
                       rev.raw_payload::text raw_payload, rev.source_updated_at, rev.ingested_at
                FROM source_record_revisions rev
                JOIN source_records sr ON sr.id = rev.source_record_id
                WHERE rev.id = :revisionId
                """).param("revisionId", revisionId).query((rs, n) -> new SourcePayload(
                rs.getObject("source_record_id", UUID.class), rs.getString("source_system"),
                rs.getString("source_record_key"), parse(rs.getString("raw_payload")),
                instant(rs, "source_updated_at"), instant(rs, "ingested_at"))).single();

        var raw = source.payload();
        String fullName = normalizeName(text(raw, "fullName"));
        String[] nameParts = fullName == null ? new String[0] : fullName.split(" ");
        String firstName = nameParts.length == 0 ? null : nameParts[0];
        String surname = nameParts.length == 0 ? null : nameParts[nameParts.length - 1];
        String email = lowerTrim(text(raw, "email"));
        String rawPhone = text(raw, "phone");
        String phone = normalizePhone(rawPhone);
        String address = normalizeAddress(text(raw, "address"));
        String postal = normalizePostal(text(raw, "postalCode"));
        String streetNumber = streetNumber(address);
        var warnings = new ArrayList<String>();
        if (rawPhone != null && phone == null) warnings.add("UNPARSEABLE_PHONE");

        UUID id = UUID.randomUUID();
        String warningJson = write(warnings);
        jdbc.sql("""
                INSERT INTO normalized_records (
                    id, source_revision_id, normalization_version, full_name, first_name, surname,
                    surname_soundex, email, phone_e164, phone_last7, address, postal_code,
                    street_number, warnings)
                VALUES (:id, :revisionId, :version, :fullName, :firstName, :surname, :soundex,
                    :email, :phone, :last7, :address, :postal, :streetNumber, CAST(:warnings AS jsonb))
                ON CONFLICT (source_revision_id, normalization_version) DO NOTHING
                """).param("id", id).param("revisionId", revisionId).param("version", version)
                .param("fullName", fullName).param("firstName", firstName).param("surname", surname)
                .param("soundex", soundex(surname)).param("email", email).param("phone", phone)
                .param("last7", last7(phone)).param("address", address).param("postal", postal)
                .param("streetNumber", streetNumber).param("warnings", warningJson).update();
        return find(revisionId, version);
    }

    public NormalizedRecord find(UUID revisionId, int version) {
        return jdbc.sql("""
                SELECT nr.*, sr.id source_record_id, sr.source_system, sr.source_record_id source_record_key,
                       rev.source_updated_at, rev.ingested_at, rev.raw_payload::text raw_payload
                FROM normalized_records nr
                JOIN source_record_revisions rev ON rev.id = nr.source_revision_id
                JOIN source_records sr ON sr.id = rev.source_record_id
                WHERE nr.source_revision_id = :revisionId AND nr.normalization_version = :version
                """).param("revisionId", revisionId).param("version", version)
                .query((rs, n) -> new NormalizedRecord(
                        rs.getObject("source_revision_id", UUID.class), rs.getObject("source_record_id", UUID.class),
                        rs.getString("source_system"), rs.getString("source_record_key"), rs.getString("full_name"),
                        rs.getString("first_name"), rs.getString("surname"), rs.getString("surname_soundex"),
                        rs.getString("email"), rs.getString("phone_e164"), rs.getString("phone_last7"),
                        rs.getString("address"), rs.getString("postal_code"), rs.getString("street_number"),
                        instant(rs, "source_updated_at"), instant(rs, "ingested_at"),
                        parse(rs.getString("raw_payload")))).optional().orElse(null);
    }

    public static String normalizeName(String input) {
        if (blank(input)) return null;
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return collapse(NAME_PUNCTUATION.matcher(normalized).replaceAll(" "));
    }

    public static String normalizeAddress(String input) {
        if (blank(input)) return null;
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        normalized = collapse(ADDRESS_PUNCTUATION.matcher(normalized).replaceAll(" "));
        return normalized
                .replaceAll("\\bstreet\\b", "st")
                .replaceAll("\\broad\\b", "rd")
                .replaceAll("\\bavenue\\b", "ave")
                .replaceAll("\\bboulevard\\b", "blvd")
                .replaceAll("\\bdrive\\b", "dr")
                .replaceAll("\\blane\\b", "ln")
                .replaceAll("\\bapartment\\b", "apt");
    }

    static String soundex(String value) {
        if (blank(value)) return null;
        String upper = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        if (upper.isEmpty()) return null;
        StringBuilder out = new StringBuilder().append(upper.charAt(0));
        char previous = code(upper.charAt(0));
        for (int i = 1; i < upper.length() && out.length() < 4; i++) {
            char current = code(upper.charAt(i));
            if (current != '0' && current != previous) out.append(current);
            previous = current;
        }
        while (out.length() < 4) out.append('0');
        return out.toString();
    }

    private String normalizePhone(String input) {
        if (blank(input)) return null;
        try {
            var parsed = phoneUtil.parse(input, properties.defaultCountry());
            if (!phoneUtil.isValidNumber(parsed)) return null;
            return phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            return null;
        }
    }

    private static char code(char c) {
        if ("BFPV".indexOf(c) >= 0) return '1';
        if ("CGJKQSXZ".indexOf(c) >= 0) return '2';
        if ("DT".indexOf(c) >= 0) return '3';
        if (c == 'L') return '4';
        if ("MN".indexOf(c) >= 0) return '5';
        if (c == 'R') return '6';
        return '0';
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String lowerTrim(String value) {
        return blank(value) ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizePostal(String value) {
        return blank(value) ? null : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String streetNumber(String address) {
        if (address == null) return null;
        var matcher = STREET_NUMBER.matcher(address);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String last7(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        return digits.length() < 7 ? null : digits.substring(digits.length() - 7);
    }

    private static String collapse(String value) { return WHITESPACE.matcher(value.trim()).replaceAll(" "); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private JsonNode parse(String json) {
        try { return objectMapper.readTree(json); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record SourcePayload(UUID sourceRecordId, String sourceSystem, String key, JsonNode payload,
                                 Instant sourceUpdatedAt, Instant ingestedAt) {}
}
