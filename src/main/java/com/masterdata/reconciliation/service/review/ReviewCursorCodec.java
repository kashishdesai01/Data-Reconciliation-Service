package com.masterdata.reconciliation.service.review;

import com.masterdata.reconciliation.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class ReviewCursorCodec {
    public Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", 2);
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Cursor is malformed");
        }
    }

    public String encode(Instant time, UUID id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((time + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public record Cursor(Instant time, UUID id) {
    }
}
